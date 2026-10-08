// Thin wrapper around tests/tools/rcon.mjs (zero-dependency RCON CLI shared by all components).
import { execFile } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const RCON_CLI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'tools', 'rcon.mjs');

/**
 * Run one or more console commands over RCON. Port/password are read from <serverDir>/server.properties.
 * @returns {Promise<string>} combined output (colour codes stripped)
 */
export function rcon(serverDir, ...commands) {
  return new Promise((resolve, reject) => {
    execFile(process.execPath, [RCON_CLI, '--dir', serverDir, ...commands], { timeout: 20000 }, (err, stdout, stderr) => {
      if (err) reject(new Error(`rcon ${commands.join(' | ')} failed: ${(stderr || err.message).trim()}`));
      else resolve(stdout);
    });
  });
}
