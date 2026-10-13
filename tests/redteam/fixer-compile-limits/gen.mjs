// node gen.mjs <modulesDir> [methods=60000]: writes modules/bigcompile/BigcompileModule.java (~3 MB, one class)
import fs from 'node:fs';
import path from 'node:path';
const dir = path.join(process.argv[2], 'bigcompile');
const n = Number(process.argv[3] || 60000);
fs.mkdirSync(dir, { recursive: true });
let s = 'package vibe.bigcompile;\n\nimport dev.vibecraft.api.ModuleContext;\nimport dev.vibecraft.api.VibeModule;\n\npublic class BigcompileModule implements VibeModule {\n';
for (let i = 0; i < n; i++) s += `    static int m${i}(int x) { return x + ${i}; }\n`;
s += '    @Override\n    public void enable(ModuleContext ctx) {\n        ctx.logger().info("big " + m1(1));\n    }\n}\n';
fs.writeFileSync(path.join(dir, 'BigcompileModule.java'), s);
console.log(`wrote ${s.length} chars`);
