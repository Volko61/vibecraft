// Fake "claude" CLI for tests: answers --version / --help / auth status, and for a job dumps its argv and
// (the presence of) selected env vars to $FAKE_CLAUDE_DUMP (default ./fake-claude-dump.json), then prints a
// stream-json conversation like the real CLI: thinking, text, tool_use (Bash curl reload, Write), tool_result,
// result. Point ai.claude-path at this file (".mjs" is run with node).
// Markers in the request: "[lent]" = 1.5 s between events (to observe a running job), "[echec]" = error result,
// "[secret]" = the assistant echoes the OpenRouter key (must be redacted by the server).
import fs from 'node:fs';

const args = process.argv.slice(2);
if (args[0] === '--version') {
  console.log('9.9.9 (Claude Code fake)');
  process.exit(0);
}
if (args[0] === '--help') {
  console.log('Usage: claude [options]\n  -p, --print\n  --output-format\n  --verbose\n  --permission-mode\n  --allowedTools\n'
    + '  --tools\n  --model\n  --resume\n  --mcp-config <configs...>\n  --strict-mcp-config\n  --permission-prompts\n'
    + '  --settings <file-or-json>');
  process.exit(0);
}
if (args[0] === 'auth') {
  console.log(JSON.stringify({ loggedIn: true, authMethod: 'fake' }));
  process.exit(0);
}
let stdin = '';
process.stdin.on('data', (c) => { stdin += c; });
process.stdin.on('end', async () => {
  const key = process.env.OPENROUTER_API_KEY || '';
  const si = args.indexOf('--settings');
  let settings = null;
  if (si >= 0) {
    try { settings = JSON.parse(fs.readFileSync(args[si + 1], 'utf8')); } catch (e) { settings = { error: String(e.message) }; }
  }
  const m = /<demande>\s*([\s\S]*?)\s*<\/demande>/.exec(stdin);
  const request = m ? m[1] : '';
  const dump = {
    argv: args,
    promptChars: stdin.length,
    request,
    promptMentionsAssets: /ASSETS\.md/.test(stdin) && /textures/.test(stdin),
    promptMentionsCompass: /boussole/.test(stdin),
    promptMentionsModels3d: /models3d/.test(stdin),
    promptProviderNote: (/Générateur d'images[^.]*\./.exec(stdin) || [''])[0],
    settings,
    env: {
      OPENROUTER_API_KEY_present: key.length > 0, OPENROUTER_API_KEY_length: key.length, VIBECRAFT_MODULE: process.env.VIBECRAFT_MODULE,
      VIBECRAFT_IMAGE_PROVIDER: process.env.VIBECRAFT_IMAGE_PROVIDER,
    },
  };
  fs.writeFileSync(process.env.FAKE_CLAUDE_DUMP || 'fake-claude-dump.json', JSON.stringify(dump, null, 2));
  const slow = /\[lent\]/.test(request);
  const fail = /\[echec\]/.test(request);
  const echoSecret = /\[secret\]/.test(request);
  const sid = 'fake-session-' + (process.env.VIBECRAFT_MODULE || 'x');
  const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');
  const pause = () => new Promise((r) => setTimeout(r, slow ? 1500 : 30));
  const mod = process.env.VIBECRAFT_MODULE || 'x';
  const api = process.env.VIBECRAFT_API || 'http://127.0.0.1:8765';
  out({ type: 'system', subtype: 'init', session_id: sid, tools: [], mcp_servers: [] });
  await pause();
  out({ type: 'assistant', session_id: sid, parent_tool_use_id: null, message: { id: 'msg_1', model: 'claude-fake', content: [
    { type: 'thinking', thinking: `Je dois créer le module ${mod}. Je vais écrire la classe puis recharger.`, signature: 'sig' }] } });
  await pause();
  out({ type: 'assistant', session_id: sid, parent_tool_use_id: null, message: { id: 'msg_1', model: 'claude-fake', content: [
    { type: 'text', text: echoSecret ? `Je regarde ca. La clé est ${key} (oups).` : 'Je regarde ca.' }] } });
  await pause();
  out({ type: 'assistant', session_id: sid, parent_tool_use_id: null, message: { id: 'msg_1', model: 'claude-fake', content: [
    { type: 'tool_use', id: 'toolu_w1', name: 'Write', input: { file_path: `G:/ws/modules/${mod}/FuseeModule.java`, content: 'class X {}' } }] } });
  await pause();
  out({ type: 'user', session_id: sid, parent_tool_use_id: null, message: { role: 'user', content: [
    { type: 'tool_result', tool_use_id: 'toolu_w1', content: 'File created successfully', is_error: false }] } });
  await pause();
  out({ type: 'assistant', session_id: sid, parent_tool_use_id: null, message: { id: 'msg_2', model: 'claude-fake', content: [
    { type: 'tool_use', id: 'toolu_r1', name: 'Bash', input: { command: `curl -s -X POST ${api}/api/modules/${mod}/reload` } }] } });
  await pause();
  out({ type: 'user', session_id: sid, parent_tool_use_id: null, message: { role: 'user', content: [
    { type: 'tool_result', tool_use_id: 'toolu_r1', content: [{ type: 'text', text: '{"ok":false,"generation":0,"error":"compilation failed: 1 error(s)"}' }], is_error: false }] } });
  await pause();
  out({ type: 'user', session_id: sid, parent_tool_use_id: 'toolu_sub', message: { role: 'user', content: [
    { type: 'tool_result', tool_use_id: 'toolu_x', content: 'sub-agent noise', is_error: false }] } });
  out({ type: 'assistant', session_id: sid, parent_tool_use_id: null, message: { id: 'msg_3', model: 'claude-fake', content: [
    { type: 'tool_use', id: 'toolu_r2', name: 'Bash', input: { command: `curl -s -X POST ${api}/api/modules/${mod}/reload` } }] } });
  await pause();
  out({ type: 'user', session_id: sid, parent_tool_use_id: null, message: { role: 'user', content: [
    { type: 'tool_result', tool_use_id: 'toolu_r2', content: '{"ok":true,"generation":3}', is_error: false }] } });
  await pause();
  if (fail) {
    out({ type: 'result', subtype: 'error_during_execution', is_error: true, session_id: sid, result: 'Echec simulé (faux claude).', total_cost_usd: 0 });
  } else {
    out({ type: 'assistant', session_id: sid, parent_tool_use_id: null, message: { id: 'msg_4', model: 'claude-fake', content: [
      { type: 'text', text: 'Fini (faux claude).' }] } });
    out({ type: 'result', subtype: 'success', is_error: false, session_id: sid, result: 'Fini (faux claude).', total_cost_usd: 0 });
  }
  process.exit(0);
});
