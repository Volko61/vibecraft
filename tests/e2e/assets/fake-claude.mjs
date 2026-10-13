// Fake "claude" CLI for tests: answers --version / --help / auth status, and for a job dumps its argv and
// (the presence of) selected env vars to $FAKE_CLAUDE_DUMP (default ./fake-claude-dump.json), then prints a
// minimal stream-json conversation. Point ai.claude-path at this file (".mjs" is run with node).
import fs from 'node:fs';

const args = process.argv.slice(2);
if (args[0] === '--version') {
  console.log('9.9.9 (Claude Code fake)');
  process.exit(0);
}
if (args[0] === '--help') {
  console.log('Usage: claude [options]\n  -p, --print\n  --output-format\n  --verbose\n  --permission-mode\n  --allowedTools\n'
    + '  --tools\n  --model\n  --resume\n  --mcp-config <configs...>\n  --strict-mcp-config\n  --permission-prompts');
  process.exit(0);
}
if (args[0] === 'auth') {
  console.log(JSON.stringify({ loggedIn: true, authMethod: 'fake' }));
  process.exit(0);
}
let stdin = '';
process.stdin.on('data', (c) => { stdin += c; });
process.stdin.on('end', () => {
  const key = process.env.OPENROUTER_API_KEY || '';
  const dump = {
    argv: args,
    promptChars: stdin.length,
    promptMentionsAssets: /ASSETS\.md/.test(stdin) && /textures/.test(stdin),
    env: { OPENROUTER_API_KEY_present: key.length > 0, OPENROUTER_API_KEY_length: key.length, VIBECRAFT_MODULE: process.env.VIBECRAFT_MODULE },
  };
  fs.writeFileSync(process.env.FAKE_CLAUDE_DUMP || 'fake-claude-dump.json', JSON.stringify(dump, null, 2));
  const sid = 'fake-session-1';
  const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');
  out({ type: 'system', subtype: 'init', session_id: sid, tools: [], mcp_servers: [] });
  out({ type: 'assistant', session_id: sid, message: { content: [{ type: 'text', text: 'Je regarde ca.' }] } });
  out({ type: 'result', subtype: 'success', is_error: false, session_id: sid, result: 'Fini (faux claude).', total_cost_usd: 0 });
  process.exit(0);
});
