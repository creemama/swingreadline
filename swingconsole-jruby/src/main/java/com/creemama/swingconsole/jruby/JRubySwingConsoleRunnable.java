package com.creemama.swingconsole.jruby;

import java.io.PrintStream;
import java.util.function.Consumer;

import org.jruby.Ruby;
import org.jruby.RubyIO;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.embed.ScriptingContainer;
import org.jruby.ext.readline.Readline;
import org.jruby.internal.runtime.GlobalVariable;
import org.jruby.internal.runtime.ValueAccessor;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.internal.runtime.methods.JavaMethod;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;

import com.creemama.swingconsole.SwingConsoleRunnable;
import com.creemama.swingconsole.TextAreaReadline;

import jline.console.completer.Completer;
import jline.console.history.History;

/**
 * A task that hooks up and runs a JRuby interactive Ruby (IRB) shell within a
 * {@link com.creemama.swingconsole.SwingConsoleDialog} or
 * {@link com.creemama.swingconsole.SwingConsoleFrame}.
 * <p>
 * The original code for this class comes from JRuby-Readline's <a href=
 * "https://github.com/jruby/jruby-readline/blob/80c8a97cc595837ec8e89154395d3f4a6ed2eee7/src/main/java/org/jruby/demo/readline/TextAreaReadline.java">TextAreaReadline</a>
 * and <a href=
 * "https://github.com/jruby/jruby-readline/blob/80c8a97cc595837ec8e89154395d3f4a6ed2eee7/src/main/java/org/jruby/demo/readline/IRBConsole.java">IRBConsole</a>.
 * </p>
 * 
 * @see SwingConsoleRunnable
 */
public class JRubySwingConsoleRunnable implements SwingConsoleRunnable {

	private final boolean redefineStandardIOStreams;

	final private Consumer<ScriptingContainer> runAfterContainerInitialization;

	/**
	 * Constructs a new {@link JRubySwingConsoleRunnable} instance.
	 * 
	 * @param redefineStandardIOStreams       whether to redefine JRuby's
	 *                                        {@code $stdin}, {@code $stdout}, and
	 *                                        {@code $stderr} streams
	 * @param runAfterContainerInitialization method used to set up the
	 *                                        {@code ScriptingContainer} after
	 *                                        container initialization (e.g.,
	 *                                        assigning variables or evaluating
	 *                                        scripts)
	 */
	public JRubySwingConsoleRunnable(boolean redefineStandardIOStreams,
			Consumer<ScriptingContainer> runAfterContainerInitialization) {
		this.redefineStandardIOStreams = redefineStandardIOStreams;
		this.runAfterContainerInitialization = runAfterContainerInitialization;
	}

	/**
	 * Hooks the <code>TextAreaReadline</code> instance into the runtime, redefining
	 * the <code>Readline</code> module so that it uses <code>tar</code>. This
	 * method does not redefine the standard input-output streams. If you need that,
	 * use {@link #hookIntoRuntimeWithStreams(Ruby, TextAreaReadline)}.
	 *
	 * @param runtime The runtime.
	 * @param tar     the text area
	 * @see #hookIntoRuntimeWithStreams(Ruby, TextAreaReadline)
	 */
	private void hookIntoRuntime(final Ruby runtime, TextAreaReadline tar) {
		/* Hack in to replace usual readline with this */
		runtime.getLoadService().require("readline");
		RubyModule readlineM = runtime.getModule("Readline");

		DynamicMethod readlineMethod = new JavaMethod.JavaMethodTwo(readlineM, Visibility.PUBLIC, "readline") {
			@Override
			public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name,
					IRubyObject arg0, IRubyObject arg1) {
				String line = tar.readLine(arg0.toString());
				if (line != null) {
					return RubyString.newUnicodeString(runtime, line);
				} else {
					return runtime.getNil();
				}
			}
		};
		readlineM.addMethod("readline", readlineMethod);
		readlineM.getSingletonClass().addMethod("readline", readlineMethod);

		History history = Readline.getHistory(Readline.getHolder(runtime));

		runtime.evalScriptlet(
				"ARGV << '--readline' << '--prompt' << 'inf-ruby';" + "require 'irb'; require 'irb/completion';");

		Completer completer = Readline.getCompletor(Readline.getHolder(runtime));

		tar.inject(completer, history);
	}

	/**
	 * Hooks the <code>TextAreaReadline</code> instance into the runtime, redefining
	 * the <code>Readline</code> module so that it uses <code>tar</code>. This
	 * method also redefines the standard input-output streams accordingly.
	 *
	 * @param runtime The runtime.
	 * @see #hookIntoRuntime(Ruby, TextAreaReadline)
	 */
	private void hookIntoRuntimeWithStreams(final Ruby runtime, TextAreaReadline tar) {
		hookIntoRuntime(runtime, tar);

		RubyIO in = new RubyIO(runtime, tar.getInputStream());
		runtime.getGlobalVariables().set("$stdin", in);

		RubyIO out = new RubyIO(runtime, tar.getOutputStream());
		out.sync_set(runtime.getTrue());
		runtime.getGlobalVariables().set("$stdout", out);
		runtime.getGlobalVariables().set("$stderr", out);
	}

	@Override
	public void run(TextAreaReadline tar) {
		ScriptingContainer container = new ScriptingContainer();
		container.setInput(tar.getInputStream());
		container.setOutput(new PrintStream(tar.getOutputStream()));
		container.setError(new PrintStream(tar.getOutputStream()));

		Ruby runtime = container.getProvider().getRuntime();

		runtime.getGlobalVariables().defineReadonly("$$",
				new ValueAccessor(runtime.newFixnum(System.identityHashCode(runtime))), GlobalVariable.Scope.GLOBAL);

		if (redefineStandardIOStreams)
			hookIntoRuntimeWithStreams(runtime, tar);
		else
			hookIntoRuntime(runtime, tar);

		if (runAfterContainerInitialization != null)
			runAfterContainerInitialization.accept(container);

		runtime.evalScriptlet("IRB.start");
	}
}