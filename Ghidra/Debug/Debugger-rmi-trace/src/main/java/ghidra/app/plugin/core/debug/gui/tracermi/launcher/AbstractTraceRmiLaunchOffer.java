/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.debug.gui.tracermi.launcher;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;

import javax.swing.Icon;

import ghidra.app.plugin.core.debug.gui.DebuggerResources;
import ghidra.app.plugin.core.debug.gui.objects.components.DebuggerMethodInvocationDialog;
import ghidra.app.plugin.core.debug.gui.tracermi.launcher.LaunchFailureDialog.ErrPromptResponse;
import ghidra.app.plugin.core.debug.service.tracermi.DefaultTraceRmiAcceptor;
import ghidra.app.plugin.core.debug.service.tracermi.TraceRmiHandler;
import ghidra.app.plugin.core.terminal.TerminalListener;
import ghidra.app.services.*;
import ghidra.app.services.DebuggerTraceManagerService.ActivationCause;
import ghidra.async.AsyncUtils;
import ghidra.dbg.target.TargetMethod.ParameterDescription;
import ghidra.dbg.util.ShellUtils;
import ghidra.debug.api.action.AutoMapSpec;
import ghidra.debug.api.modules.DebuggerMissingProgramActionContext;
import ghidra.debug.api.modules.DebuggerStaticMappingChangeListener;
import ghidra.debug.api.tracermi.*;
import ghidra.framework.options.SaveState;
import ghidra.framework.plugintool.AutoConfigState.*;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.pty.*;
import ghidra.trace.model.Trace;
import ghidra.trace.model.TraceLocation;
import ghidra.util.*;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.Task;
import ghidra.util.task.TaskMonitor;

public abstract class AbstractTraceRmiLaunchOffer implements TraceRmiLaunchOffer {
	public static final String PARAM_DISPLAY_IMAGE = "Image";
	public static final String PREFIX_PARAM_EXTTOOL = "env:GHIDRA_LANG_EXTTOOL_";

	public static final int DEFAULT_TIMEOUT_MILLIS = 10000;

	protected record PtyTerminalSession(Terminal terminal, Pty pty, PtySession session,
			Thread waiter) implements TerminalSession {
		@Override
		public void terminate() throws IOException {
			terminal.terminated();
			session.destroyForcibly();
			pty.close();
			waiter.interrupt();
		}

		@Override
		public String description() {
			return session.description();
		}
	}

	protected record NullPtyTerminalSession(Terminal terminal, Pty pty, String name)
			implements TerminalSession {
		@Override
		public void terminate() throws IOException {
			terminal.terminated();
			pty.close();
		}

		@Override
		public String description() {
			return name;
		}
	}

	static class TerminateSessionTask extends Task {
		private final TerminalSession session;

		public TerminateSessionTask(TerminalSession session) {
			super("Terminate Session", false, false, false);
			this.session = session;
		}

		@Override
		public void run(TaskMonitor monitor) throws CancelledException {
			try {
				session.close();
			}
			catch (IOException e) {
				Msg.error(this, "Could not terminate: " + e, e);
			}
		}
	}

	protected final TraceRmiLauncherServicePlugin plugin;
	protected final Program program;
	protected final PluginTool tool;
	protected final TerminalService terminalService;

	public AbstractTraceRmiLaunchOffer(TraceRmiLauncherServicePlugin plugin, Program program) {
		this.plugin = Objects.requireNonNull(plugin);
		this.program = program;
		this.tool = plugin.getTool();
		this.terminalService = Objects.requireNonNull(tool.getService(TerminalService.class));
	}

	@Override
	public boolean equals(Object obj) {
		if (this.getClass() != obj.getClass()) {
			return false;
		}
		AbstractTraceRmiLaunchOffer other = (AbstractTraceRmiLaunchOffer) obj;
		return this.getConfigName().equals(other.getConfigName());
	}

	protected int getTimeoutMillis() {
		return DEFAULT_TIMEOUT_MILLIS;
	}

	protected int getConnectionTimeoutMillis() {
		return getTimeoutMillis();
	}

	@Override
	public Icon getIcon() {
		return DebuggerResources.ICON_DEBUGGER;
	}

	protected Address getMappingProbeAddress() {
		// May be null, in which case, we won't wait for a mapping
		return DebuggerMissingProgramActionContext.getMappingProbeAddress(program);
	}

	protected CompletableFuture<Void> listenForMapping(DebuggerStaticMappingService mappingService,
			TraceRmiConnection connection, Trace trace) {
		Address probeAddress = getMappingProbeAddress();
		if (probeAddress == null) {
			return AsyncUtils.nil(); // No need to wait on mapping of nothing
		}
		ProgramLocation probe = new ProgramLocation(program, probeAddress);
		var result = new CompletableFuture<Void>() {
			DebuggerStaticMappingChangeListener listener = (affectedTraces, affectedPrograms) -> {
				if (!affectedPrograms.contains(program) &&
					!affectedTraces.contains(trace)) {
					return;
				}
				check();
			};

			protected void check() {
				long snap = connection.getLastSnapshot(trace);
				TraceLocation result = mappingService.getOpenMappedLocation(trace, probe, snap);
				if (result == null) {
					return;
				}
				complete(null);
				mappingService.removeChangeListener(listener);
			}
		};
		mappingService.addChangeListener(result.listener);
		result.check();
		result.exceptionally(ex -> {
			mappingService.removeChangeListener(result.listener);
			return null;
		});
		return result;
	}

	protected boolean invokeMapper(TaskMonitor monitor, DebuggerStaticMappingService mappingService,
			TraceRmiConnection connection, Trace trace, AutoMapSpec spec)
			throws CancelledException {
		if (program == null) {
			return false;
		}

		if (spec.performMapping(mappingService, trace, List.of(program), monitor)) {
			return true;
		}

		try {
			mappingService.changesSettled().get(1, TimeUnit.SECONDS);
		}
		catch (InterruptedException | ExecutionException | TimeoutException e) {
			// Whatever, just check for the mapping
		}

		Address probeAddress = getMappingProbeAddress();
		if (probeAddress == null) {
			return true; // Probably shouldn't happen, but if it does, say "success"
		}
		ProgramLocation probe = new ProgramLocation(program, probeAddress);
		long snap = connection.getLastSnapshot(trace);
		return mappingService.getOpenMappedLocation(trace, probe, snap) != null;
	}

	protected SaveState saveLauncherArgsToState(Map<String, ?> args,
			Map<String, ParameterDescription<?>> params) {
		SaveState state = new SaveState();
		for (ParameterDescription<?> param : params.values()) {
			Object val = args.get(param.name);
			if (val != null) {
				ConfigStateField.putState(state, param.type.asSubclass(Object.class),
					"param_" + param.name, val);
			}
		}
		return state;
	}

	protected void saveState(SaveState state) {
		if (program == null) {
			plugin.writeToolLaunchConfig(getConfigName(), state);
			return;
		}
		plugin.writeProgramLaunchConfig(program, getConfigName(), state);
	}

	protected void saveLauncherArgs(Map<String, ?> args,
			Map<String, ParameterDescription<?>> params) {
		saveState(saveLauncherArgsToState(args, params));
	}

	interface ImageParamSetter {
		@SuppressWarnings("unchecked")
		static ImageParamSetter get(ParameterDescription<?> param) {
			if (param.type == String.class) {
				return new StringImageParamSetter((ParameterDescription<String>) param);
			}
			if (param.type == PathIsFile.class) {
				return new FileImageParamSetter((ParameterDescription<PathIsFile>) param);
			}
			Msg.warn(ImageParamSetter.class,
				"'Image' parameter has unsupported type: " + param.type);
			return null;
		}

		void setImage(Map<String, Object> map, Program program);
	}

	static class StringImageParamSetter implements ImageParamSetter {
		private final ParameterDescription<String> param;

		public StringImageParamSetter(ParameterDescription<String> param) {
			this.param = param;
		}

		@Override
		public void setImage(Map<String, Object> map, Program program) {
			// str-type Image is a hint that the launcher is remote
			String value = TraceRmiLauncherServicePlugin.getProgramPath(program, false);
			param.set(map, value);
		}
	}

	static class FileImageParamSetter implements ImageParamSetter {
		private final ParameterDescription<PathIsFile> param;

		public FileImageParamSetter(ParameterDescription<PathIsFile> param) {
			this.param = param;
		}

		@Override
		public void setImage(Map<String, Object> map, Program program) {
			// file-type Image is a hint that the launcher is local
			String str = TraceRmiLauncherServicePlugin.getProgramPath(program, true);
			PathIsFile value = str == null ? null : new PathIsFile(Paths.get(str));
			param.set(map, value);
		}
	}

	/**
	 * Generate the default launcher arguments
	 * 
	 * <p>
	 * It is not sufficient to simply take the defaults specified in the parameters. This must
	 * populate the arguments necessary to launch the requested program.
	 * 
	 * @param params the parameters
	 * @return the default arguments
	 */
	@SuppressWarnings("unchecked")
	protected Map<String, ?> generateDefaultLauncherArgs(
			Map<String, ParameterDescription<?>> params) {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		ImageParamSetter imageSetter = null;
		for (Entry<String, ParameterDescription<?>> entry : params.entrySet()) {
			ParameterDescription<?> param = entry.getValue();
			map.put(entry.getKey(), param.defaultValue);
			if (PARAM_DISPLAY_IMAGE.equals(param.display)) {
				imageSetter = ImageParamSetter.get(param);
				// May still be null if type is not supported
			}
			else if (param.name.startsWith(PREFIX_PARAM_EXTTOOL)) {
				String tool = param.name.substring(PREFIX_PARAM_EXTTOOL.length());
				List<String> names =
					program.getLanguage().getLanguageDescription().getExternalNames(tool);
				if (names != null && !names.isEmpty()) {
					if (param.type == String.class) {
						var paramStr = (ParameterDescription<String>) param;
						paramStr.set(map, names.get(0));
					}
					else if (param.type == PathIsFile.class) {
						var paramPIF = (ParameterDescription<PathIsFile>) param;
						paramPIF.set(map, PathIsFile.fromString(names.get(0)));
					}
					else if (param.type == PathIsDir.class) {
						var paramPID = (ParameterDescription<PathIsDir>) param;
						paramPID.set(map, PathIsDir.fromString(names.get(0)));
					}
				}
			}
		}
		if (imageSetter != null && program != null) {
			imageSetter.setImage(map, program);
		}
		return map;
	}

	/**
	 * Prompt the user for arguments, showing those last used or defaults
	 * 
	 * @param lastExc
	 * 
	 * @param params the parameters of the model's launcher
	 * @param lastExc if re-prompting, an error to display
	 * @return the arguments given by the user, or null if cancelled
	 */
	protected Map<String, ?> promptLauncherArgs(LaunchConfigurator configurator,
			Throwable lastExc) {
		Map<String, ParameterDescription<?>> params = getParameters();
		DebuggerMethodInvocationDialog dialog =
			new DebuggerMethodInvocationDialog(tool, getTitle(), "Launch", getIcon());
		dialog.setDescription(getDescription());
		dialog.setHelpLocation(getHelpLocation());
		// NB. Do not invoke read/writeConfigState
		Map<String, ?> args;
		boolean reset = false;
		do {
			args =
				configurator.configureLauncher(this, loadLastLauncherArgs(true), RelPrompt.BEFORE);
			for (ParameterDescription<?> param : params.values()) {
				Object val = args.get(param.name);
				if (val != null) {
					dialog.setMemorizedArgument(param.name, param.type.asSubclass(Object.class),
						val);
				}
			}
			if (lastExc != null) {
				dialog.setStatusText(lastExc.toString(), MessageType.ERROR);
			}
			else {
				dialog.setStatusText("");
			}
			args = dialog.promptArguments(params);
			if (args == null) {
				// Cancelled
				return null;
			}
			reset = dialog.isResetRequested();
			if (reset) {
				args = generateDefaultLauncherArgs(params);
			}
			saveLauncherArgs(args, params);
		}
		while (reset);
		return args;
	}

	/**
	 * Load the arguments last used for this offer, or give the defaults
	 * 
	 * <p>
	 * If there are no saved "last used" arguments, then this will return the defaults. If there are
	 * saved arguments, but they cannot be loaded, then this will behave differently depending on
	 * whether the user will be confirming the arguments. If there will be no prompt/confirmation,
	 * then this method must throw an exception in order to avoid launching with defaults, when the
	 * user may be expecting a customized launch. If there will be a prompt, then this may safely
	 * return the defaults, since the user will be given a chance to correct them.
	 * 
	 * @param forPrompt true if the user will be confirming the arguments
	 * @return the loaded arguments, or defaults
	 */
	protected Map<String, ?> loadLastLauncherArgs(boolean forPrompt) {
		Map<String, ParameterDescription<?>> params = getParameters();
		Map<String, ?> args = loadLauncherArgsFromState(loadState(forPrompt), params);
		saveLauncherArgs(args, params);
		return args;
	}

	protected Map<String, ?> loadLauncherArgsFromState(SaveState state,
			Map<String, ParameterDescription<?>> params) {
		Map<String, ?> defaultArgs = generateDefaultLauncherArgs(params);
		if (state == null) {
			return defaultArgs;
		}
		List<String> names = List.of(state.getNames());
		Map<String, Object> args = new LinkedHashMap<>();
		for (ParameterDescription<?> param : params.values()) {
			String key = "param_" + param.name;
			Object configState =
				names.contains(key) ? ConfigStateField.getState(state, param.type, key) : null;
			if (configState != null) {
				args.put(param.name, configState);
			}
			else {
				args.put(param.name, defaultArgs.get(param.name));
			}
		}
		return args;
	}

	protected SaveState loadState(boolean forPrompt) {
		if (program == null) {
			return plugin.readToolLaunchConfig(getConfigName());
		}
		return plugin.readProgramLaunchConfig(program, getConfigName(), forPrompt);
	}

	/**
	 * Obtain the launcher args
	 * 
	 * <p>
	 * This should either call {@link #promptLauncherArgs(LaunchConfigurator, Throwable)} or
	 * {@link #loadLastLauncherArgs(boolean)}. Note if choosing the latter, the user will not be
	 * prompted to confirm.
	 * 
	 * @param prompt true to prompt the user, false to use saved arguments
	 * @param configurator the rules for configuring the launcher
	 * @param lastExc if retrying, the last exception to display as an error message
	 * @return the chosen arguments, or null if the user cancels at the prompt
	 */
	public Map<String, ?> getLauncherArgs(boolean prompt, LaunchConfigurator configurator,
			Throwable lastExc) {
		return prompt
				? configurator.configureLauncher(this, promptLauncherArgs(configurator, lastExc),
					RelPrompt.AFTER)
				: configurator.configureLauncher(this, loadLastLauncherArgs(false), RelPrompt.NONE);
	}

	public Map<String, ?> getLauncherArgs(boolean prompt) {
		return getLauncherArgs(prompt, LaunchConfigurator.NOP, null);
	}

	protected PtyFactory getPtyFactory() {
		return PtyFactory.local();
	}

	protected PtyTerminalSession runInTerminal(List<String> commandLine, Map<String, String> env,
			File workingDirectory, Collection<TerminalSession> subordinates) throws IOException {
		PtyFactory factory = getPtyFactory();
		Pty pty = factory.openpty();

		PtyParent parent = pty.getParent();
		PtyChild child = pty.getChild();
		Terminal terminal = terminalService.createWithStreams(plugin, Charset.forName("UTF-8"),
			parent.getInputStream(), parent.getOutputStream());

		List<String> withoutPath = ShellUtils.removePath(commandLine);
		terminal.setSubTitle(ShellUtils.generateLine(withoutPath));
		TerminalListener resizeListener = new TerminalListener() {
			@Override
			public void resized(short cols, short rows) {
				try {
					child.setWindowSize(cols, rows);
				}
				catch (Exception e) {
					Msg.error(this, "Could not resize pty: " + e);
				}
			}
		};
		terminal.addTerminalListener(resizeListener);

		env.put("TERM", "xterm-256color");
		PtySession session =
			pty.getChild().session(commandLine.toArray(String[]::new), env, workingDirectory);

		Thread waiter = new Thread(() -> {
			try {
				session.waitExited();
				terminal.terminated();
				pty.close();

				for (TerminalSession ss : subordinates) {
					ss.terminate();
				}
			}
			catch (InterruptedException | IOException e) {
				Msg.error(this, e);
			}
		}, "Waiter: " + getConfigName());
		waiter.start();

		PtyTerminalSession terminalSession =
			new PtyTerminalSession(terminal, pty, session, waiter);
		terminal.setTerminateAction(() -> {
			tool.execute(new TerminateSessionTask(terminalSession));
		});
		return terminalSession;
	}

	protected NullPtyTerminalSession nullPtyTerminal() throws IOException {
		PtyFactory factory = getPtyFactory();
		Pty pty = factory.openpty();

		PtyParent parent = pty.getParent();
		PtyChild child = pty.getChild();
		Terminal terminal = terminalService.createWithStreams(plugin, Charset.forName("UTF-8"),
			parent.getInputStream(), parent.getOutputStream());
		TerminalListener resizeListener = new TerminalListener() {
			@Override
			public void resized(short cols, short rows) {
				child.setWindowSize(cols, rows);
			}
		};
		terminal.addTerminalListener(resizeListener);

		String name = pty.getChild().nullSession();
		terminal.setSubTitle(name);

		NullPtyTerminalSession terminalSession = new NullPtyTerminalSession(terminal, pty, name);
		terminal.setTerminateAction(() -> {
			tool.execute(new TerminateSessionTask(terminalSession));
		});
		return terminalSession;
	}

	protected abstract void launchBackEnd(TaskMonitor monitor,
			Map<String, TerminalSession> sessions, Map<String, ?> args, SocketAddress address)
			throws Exception;

	static class NoStaticMappingException extends Exception {
		public NoStaticMappingException(String message) {
			super(message);
		}

		@Override
		public String toString() {
			return getMessage();
		}
	}

	protected void initializeMonitor(TaskMonitor monitor) {
		DebuggerAutoMappingService auto = tool.getService(DebuggerAutoMappingService.class);
		AutoMapSpec spec = auto.getAutoMapSpec();
		if (requiresImage() && spec.hasTask()) {
			monitor.setMaximum(6);
		}
		else {
			monitor.setMaximum(5);
		}
	}

	protected void waitForModuleMapping(TaskMonitor monitor, TraceRmiHandler connection,
			Trace trace) throws CancelledException, InterruptedException, ExecutionException,
			NoStaticMappingException {
		if (!requiresImage()) {
			return;
		}
		DebuggerAutoMappingService auto = tool.getService(DebuggerAutoMappingService.class);
		AutoMapSpec spec = auto.getAutoMapSpec(trace);
		if (!spec.hasTask()) {
			return;
		}
		DebuggerStaticMappingService mappingService =
			tool.getService(DebuggerStaticMappingService.class);
		monitor.setMessage("Waiting for module mapping");
		try {
			listenForMapping(mappingService, connection, trace).get(getTimeoutMillis(),
				TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			monitor.setMessage(
				"Timed out waiting for module mapping. Invoking the mapper.");
			boolean mapped;
			try {
				mapped = invokeMapper(monitor, mappingService, connection, trace, spec);
			}
			catch (CancelledException ce) {
				throw new CancellationException(e.getMessage());
			}
			if (!mapped) {
				throw new NoStaticMappingException(
					"The resulting target process has no mapping to the static image.");
			}
		}
		monitor.increment();
	}

	@Override
	public LaunchResult launchProgram(TaskMonitor monitor, LaunchConfigurator configurator) {
		if (requiresImage() && program == null) {
			throw new IllegalStateException("Offer requires image, but no program given.");
		}
		InternalTraceRmiService service = tool.getService(InternalTraceRmiService.class);
		DebuggerTraceManagerService traceManager =
			tool.getService(DebuggerTraceManagerService.class);
		final PromptMode mode = configurator.getPromptMode();
		boolean prompt = mode == PromptMode.ALWAYS;

		DefaultTraceRmiAcceptor acceptor = null;
		Map<String, TerminalSession> sessions = new LinkedHashMap<>();
		TraceRmiHandler connection = null;
		Trace trace = null;
		Throwable lastExc = null;

		initializeMonitor(monitor);
		while (true) {
			try {
				monitor.setMessage("Gathering arguments");
				Map<String, ?> args = getLauncherArgs(prompt, configurator, lastExc);
				if (args == null) {
					if (lastExc == null) {
						lastExc = new CancelledException();
					}
					return new LaunchResult(program, sessions, acceptor, connection, trace,
						lastExc);
				}
				monitor.increment();

				acceptor = null;
				sessions.clear();
				connection = null;
				trace = null;
				lastExc = null;

				monitor.setMessage("Listening for connection");
				acceptor = service.acceptOne(new InetSocketAddress("127.0.0.1", 0));
				monitor.increment();

				monitor.setMessage("Launching back-end");
				launchBackEnd(monitor, sessions, args, acceptor.getAddress());
				monitor.increment();

				monitor.setMessage("Waiting for connection");
				acceptor.setTimeout(getConnectionTimeoutMillis());
				connection = acceptor.accept();
				connection.registerTerminals(sessions.values());
				monitor.increment();

				monitor.setMessage("Waiting for trace");
				trace = connection.waitForTrace(getTimeoutMillis());
				traceManager.openTrace(trace);
				traceManager.activate(traceManager.resolveTrace(trace),
					ActivationCause.START_RECORDING);
				monitor.increment();

				waitForModuleMapping(monitor, connection, trace);
			}
			catch (CancelledException e) {
				lastExc = e;
				LaunchResult result =
					new LaunchResult(program, sessions, acceptor, connection, trace, lastExc);
				try {
					result.close();
				}
				catch (Exception e1) {
					Msg.error(this, "Could not close", e1);
				}
				return new LaunchResult(program, Map.of(), null, null, null, lastExc);
			}
			catch (NoStaticMappingException e) {
				DebuggerConsoleService consoleService =
					tool.getService(DebuggerConsoleService.class);
				if (consoleService == null) {
					Msg.error(this, e.getMessage());
				}
				else {
					consoleService.log(DebuggerResources.ICON_MODULES,
						"<html>The trace <b>%s</b> has no mapping to its program <b>%s</b></html>"
								.formatted(
									HTMLUtilities.escapeHTML(trace.getDomainFile().getName()),
									HTMLUtilities.escapeHTML(program.getDomainFile().getName())),
						new DebuggerMissingProgramActionContext(trace, program));
				}
				return new LaunchResult(program, sessions, acceptor, connection, trace, e);
			}
			catch (Exception e) {
				DebuggerConsoleService consoleService =
					tool.getService(DebuggerConsoleService.class);
				if (consoleService != null) {
					consoleService.log(DebuggerResources.ICON_LOG_ERROR,
						"Launch %s Failed".formatted(getTitle()), e);
				}
				lastExc = e;
				prompt = mode != PromptMode.NEVER;
				LaunchResult result =
					new LaunchResult(program, sessions, acceptor, connection, trace, lastExc);
				if (prompt) {
					switch (promptError(result)) {
						case KEEP:
							result.showTerminals();
							return result;
						case RETRY:
							try {
								result.close();
							}
							catch (Exception e1) {
								Msg.error(this, "Could not close", e1);
							}
							continue;
						case TERMINATE:
							try {
								result.close();
							}
							catch (Exception e1) {
								Msg.error(this, "Could not close", e1);
							}
							return new LaunchResult(program, Map.of(), null, null, null, lastExc);
					}
					continue;
				}
				return result;
			}
			return new LaunchResult(program, sessions, acceptor, connection, trace, null);
		}
	}

	protected ErrPromptResponse promptError(LaunchResult result) {
		return LaunchFailureDialog.show(result, getHelpLocation());
	}
}
