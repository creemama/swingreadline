package com.creemama.swingreadline.jruby;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.List;

import org.jruby.Ruby;
import org.jruby.RubyIO;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.ScriptingContainer;
import org.jruby.ext.readline.Readline;
import org.jruby.internal.runtime.GlobalVariable;
import org.jruby.internal.runtime.ValueAccessor;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.internal.runtime.methods.JavaMethod;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;

import com.creemama.swingreadline.JReadlineFrameModel;
import com.creemama.swingreadline.TextAreaReadline;

import jline.console.completer.Completer;
import jline.console.history.History;

/**
 * The model for a JRuby {@link com.creemama.swingreadline.JReadlineFrame}.
 * <p>
 * The original code for this class comes from JRuby-Readline's <a href=
 * "https://github.com/jruby/jruby-readline/blob/80c8a97cc595837ec8e89154395d3f4a6ed2eee7/src/main/java/org/jruby/demo/readline/TextAreaReadline.java">TextAreaReadline</a>
 * and <a href=
 * "https://github.com/jruby/jruby-readline/blob/80c8a97cc595837ec8e89154395d3f4a6ed2eee7/src/main/java/org/jruby/demo/readline/IRBConsole.java">IRBConsole</a>.
 * </p>
 * 
 * @see JReadlineFrameModel
 */
public class JRubyReadlineFrameModel implements JReadlineFrameModel {

	private final boolean redefineStandardIOStreams;

	private Ruby runtime;

	/**
	 * See https://github.com/jruby/jruby/wiki/RedBridge and the Javadoc for
	 * {@link ScriptingContainer}.
	 */
	private ScriptingContainer container;

	/**
	 * Constructs a new {@link JRubyReadlineFrameModel} instance.
	 * 
	 * @param redefineStandardIOStreams whether to redefine JRuby's {@code $stdin},
	 *                                  {@code $stdout}, and {@code $stderr} streams
	 */
	public JRubyReadlineFrameModel(boolean redefineStandardIOStreams) {
		this.redefineStandardIOStreams = redefineStandardIOStreams;
	}

	@Override
	public void setUp(List<String> list, TextAreaReadline tar) {
		final RubyInstanceConfig config = new RubyInstanceConfig() {
			{
				setInput(tar.getInputStream());
				setOutput(new PrintStream(tar.getOutputStream()));
				setError(new PrintStream(tar.getOutputStream()));
				setArgv(list.toArray(new String[0]));
			}
		};
		final Ruby runtime = Ruby.newInstance(config);

		// Create a scripting container attached to runtime.
		container = new ScriptingContainer(LocalContextScope.SINGLETHREAD);

		runtime.getGlobalVariables().defineReadonly("$$",
				new ValueAccessor(runtime.newFixnum(System.identityHashCode(runtime))), GlobalVariable.Scope.GLOBAL);

		if (redefineStandardIOStreams)
			hookIntoRuntimeWithStreams(runtime, tar);
		else
			hookIntoRuntime(runtime, tar);
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
		this.runtime = runtime;

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
	public void runScript(File script) throws IOException {
		try (Reader reader = new FileReader(script, Charset.forName("UTF-8"))) {
			container.runScriptlet(reader, script.getPath());
		}
	}

	@Override
	public void putVariable(String variableName, Object value) {
		container.put(variableName, value);
	}

	@Override
	public void run(TextAreaReadline tar) {
		runtime.evalScriptlet("IRB.start");
	}
}
