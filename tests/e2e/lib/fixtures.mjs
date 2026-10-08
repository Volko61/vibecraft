// Test module sources (tests/e2e/fixtures/<name>/) and the variants scenarios derive from them.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { packageFor } from './workspace.mjs';

export const FIXTURES_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'fixtures');

/** All fixture names (directories of tests/e2e/fixtures). */
export function fixtureNames() {
  return fs.readdirSync(FIXTURES_DIR, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name);
}

/** Raw files of a fixture: {relPath: content}. */
export function loadFixture(name) {
  const dir = path.join(FIXTURES_DIR, name);
  const out = {};
  const walk = (d, rel) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const r = rel ? rel + '/' + e.name : e.name;
      if (e.isDirectory()) walk(path.join(d, e.name), r);
      else out[r] = fs.readFileSync(path.join(d, e.name), 'utf8');
    }
  };
  walk(dir, '');
  return out;
}

/**
 * Render a fixture for a module id.
 * - every `package vibe.xxx;` becomes the recommended package of `id`
 * - `%TOKEN%` placeholders (only used inside string literals) are replaced from `vars`
 * - `/*@POINT@*\/` injection comments are replaced from `inject`
 * @param {string} name     fixture directory name
 * @param {object} o
 * @param {string} o.id     module id
 * @param {Record<string,string>} [o.vars]    e.g. {V: 'run1.v2', P: 'e2ehot'}
 * @param {Record<string,string>} [o.inject]  e.g. {ENABLE: 'int broken = "x";'}
 * @param {Record<string,string|null>} [o.extra] extra files (null removes a fixture file)
 */
export function renderFixture(name, { id, vars = {}, inject = {}, extra = {} } = {}) {
  const files = loadFixture(name);
  for (const [k, v] of Object.entries(extra)) {
    if (v == null) delete files[k];
    else files[k] = v;
  }
  const out = {};
  for (const [rel, content0] of Object.entries(files)) {
    let content = content0;
    if (rel.endsWith('.java') && id) {
      content = content.replace(/^package\s+[\w.]+\s*;/m, `package ${packageFor(id)};`);
    }
    content = content.replace(/%([A-Z][A-Z0-9_]*)%/g, (m, k) => (k in vars ? String(vars[k]) : m));
    content = content.replace(/\/\*@([A-Z][A-Z0-9_]*)@\*\//g, (m, k) => (k in inject ? inject[k] : m));
    out[rel] = content;
  }
  return out;
}

// ------------------------------------------------------------------ variants

/** A compile error (type mismatch) injected in enable() of the "hot" fixture; reported on that line. */
export const COMPILE_ERROR = {
  inject: { ENABLE: 'int e2eBroken = "this is not an int"; // E2E-COMPILE-ERROR' },
  file: 'HotModule.java',
  marker: 'E2E-COMPILE-ERROR',
};

/** A syntax error (missing semicolon + unbalanced brace) like a half-written file. */
export const SYNTAX_ERROR = {
  inject: { ENABLE: 'ctx.logger().info("half written" // E2E-SYNTAX-ERROR\n        if (true {' },
  file: 'HotModule.java',
  marker: 'E2E-SYNTAX-ERROR',
};

/**
 * Forbidden calls (ARCHITECTURE.md 4.3 "Policy"), injected inside the command handler of the "hot"
 * fixture behind a condition that is never true at runtime: they must be rejected AT LOAD time.
 * Variables in scope: sender (CommandSender), args (String[]).
 */
export const FORBIDDEN = {
  'System.exit': 'System.exit(0);',
  'Runtime.exit': 'Runtime.getRuntime().exit(0);',
  'Runtime.halt': 'Runtime.getRuntime().halt(0);',
  'Runtime.exec': 'Runtime.getRuntime().exec(new String[] {"cmd", "/c", "echo", "pwned"});',
  'ProcessBuilder': 'new ProcessBuilder("cmd", "/c", "echo").start();',
  'Thread.stop': 'Thread.currentThread().stop();',
  'Bukkit.shutdown': 'org.bukkit.Bukkit.shutdown();',
  'Server.shutdown': 'sender.getServer().shutdown();',
  'Bukkit.reload': 'org.bukkit.Bukkit.reload();',
  'Server.reloadData': 'sender.getServer().reloadData();',
  'PluginManager.disablePlugin': 'org.bukkit.Bukkit.getPluginManager().disablePlugin(org.bukkit.Bukkit.getPluginManager().getPlugin("VibeCraft"));',
  'PluginManager.clearPlugins': 'org.bukkit.Bukkit.getPluginManager().clearPlugins();',
  'HandlerList.unregisterAll()': 'org.bukkit.event.HandlerList.unregisterAll();',
  'HandlerList.unregisterAll(Plugin)': 'org.bukkit.event.HandlerList.unregisterAll(org.bukkit.Bukkit.getPluginManager().getPlugin("VibeCraft"));',
  'BukkitScheduler.cancelTasks': 'org.bukkit.Bukkit.getScheduler().cancelTasks(org.bukkit.Bukkit.getPluginManager().getPlugin("VibeCraft"));',
  'System.setSecurityManager': 'System.setSecurityManager(null);',
  'sun.misc.Unsafe': 'sender.sendMessage(sun.misc.Unsafe.class.getName());',
};

/**
 * Forbidden calls hidden in less obvious bytecode shapes (same injection point as FORBIDDEN): method references
 * (the call is an invokedynamic bootstrap argument, not an INVOKE instruction) and a forbidden constructor reference.
 */
export const FORBIDDEN_INDIRECT = {
  'System::exit (method ref)': 'java.util.function.IntConsumer e2eExit = System::exit; e2eExit.accept(0);',
  'Runtime::halt (bound method ref)': 'java.util.function.IntConsumer e2eHalt = Runtime.getRuntime()::halt; e2eHalt.accept(0);',
  'ProcessBuilder::new (ctor ref)': 'java.util.function.Function<String[], ProcessBuilder> e2ePb = ProcessBuilder::new; e2ePb.apply(new String[] {"cmd"}).start();',
  'Bukkit::shutdown (method ref)': 'Runnable e2eStop = org.bukkit.Bukkit::shutdown; e2eStop.run();',
};

/** Wrap a forbidden snippet so it compiles but never runs. */
export function forbiddenInjection(snippet) {
  return `if (args.length == 4242 && sender.getName().equals("nobody-e2e")) { ${snippet} }`;
}

/** A forbidden call in ANOTHER class of the module (a helper never invoked): must be rejected too, with that file in the diagnostic. */
export function forbiddenHelperFile(pkg) {
  return {
    file: 'Evil.java',
    content: `package ${pkg};\n\nfinal class Evil {\n    private Evil() {\n    }\n\n    static void neverCalled() {\n        Runtime.getRuntime().halt(1); // E2E-FORBIDDEN-HELPER\n    }\n}\n`,
    marker: 'E2E-FORBIDDEN-HELPER',
  };
}

/**
 * Module-class problems (ARCHITECTURE.md 3 / 4.1: "exactly one concrete public class implementing VibeModule with a
 * public no-arg constructor"). Each variant transforms the rendered files of the "hot" fixture.
 * expect: 'fail' (the load attempt must fail: rollback / FAILED) or 'ok' (must load).
 * errorRe: what the error message should talk about (checked softly).
 */
export const MODULE_CLASS_VARIANTS = [
  {
    name: 'two module classes',
    expect: 'fail',
    errorRe: /VibeModule|module class|several|multiple|more than one|2/i,
    apply: (files, pkg) => ({ ...files, 'Second.java': `package ${pkg};\n\nimport dev.vibecraft.api.ModuleContext;\nimport dev.vibecraft.api.VibeModule;\n\npublic class Second implements VibeModule {\n    @Override\n    public void enable(ModuleContext ctx) {\n        ctx.command("e2esecond", (s, a) -> s.sendMessage("E2E-SECOND"));\n    }\n}\n` }),
  },
  {
    name: 'no module class',
    expect: 'fail',
    errorRe: /VibeModule|module class|no .*class|none|not found/i,
    apply: (files, pkg) => ({ 'Fmt.java': files['Fmt.java'], 'HotModule.java': `package ${pkg};\n\n/** Forgot "implements VibeModule". */\npublic class HotModule {\n    public void enable() {\n    }\n}\n` }),
  },
  {
    name: 'no public no-arg constructor',
    expect: 'fail',
    errorRe: /constructor|no-arg|instantiat|<init>|NoSuchMethod/i,
    apply: (files) => ({ ...files, 'HotModule.java': files['HotModule.java'].replace(/(public class HotModule implements VibeModule \{)/, '$1\n    public HotModule(int e2eNoDefaultCtor) {\n    }\n') }),
  },
  {
    name: 'ctx.on(abstract event without handler list)',
    expect: 'fail',
    errorRe: /PlayerEvent|handler ?list|abstract/i,
    apply: (files) => ({ ...files, 'HotModule.java': files['HotModule.java'].replace('/*@ENABLE@*/', 'ctx.on(org.bukkit.event.player.PlayerEvent.class, e -> { }); // E2E-ABSTRACT-EVENT') }),
  },
  {
    name: 'abstract base class + one concrete module class',
    expect: 'ok',
    apply: (files, pkg) => ({
      ...files,
      'Base.java': `package ${pkg};\n\nimport dev.vibecraft.api.ModuleContext;\nimport dev.vibecraft.api.VibeModule;\n\n/** Abstract: must not count as a second module class. */\npublic abstract class Base implements VibeModule {\n    @Override\n    public void disable(ModuleContext ctx) {\n        ctx.logger().info("base disable");\n    }\n}\n`,
      'HotModule.java': files['HotModule.java'].replace('public class HotModule implements VibeModule', 'public class HotModule extends Base'),
    }),
  },
];
