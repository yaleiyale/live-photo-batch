// Structurizr DSL 的结构自检（无依赖，纯 Node）。本机没有 Java 就跑不了官方解析器，
// 这个脚本退而求其次检查四类最容易写错的东西：花括号平衡、标识符是否已声明、
// 视图目标是否存在、各关键字的位置参数是否超限。它**不能**证明语义正确，只能挡住手误。
//   node dsl-check.mjs                                    # 检查同目录的 live-photo-batch.structurizr.dsl
//   node dsl-check.mjs other.dsl
import { readFileSync } from "node:fs";
import { join } from "node:path";

const file = join(import.meta.dirname, process.argv[2] ?? "live-photo-batch.structurizr.dsl");
const text = readFileSync(file, "utf8");
const lines = text.split(/\r?\n/);

// 1) 花括号平衡
let depth = 0, firstBadLine = 0;
lines.forEach((l, i) => {
  for (const ch of l) { if (ch === "{") depth++; else if (ch === "}") depth--; if (depth < 0 && !firstBadLine) firstBadLine = i + 1; }
});

// 2) 沿缩进/花括号维护容器路径，登记每个元素的完整标识符
const paths = new Set();
const leaves = new Set();
const stack = [];
let inViews = false;
for (const raw of lines) {
  const l = raw.trim();
  if (/^views\b/.test(l)) inViews = true;
  const decl = l.match(/^(\w+)\s*=\s*(person|softwareSystem|container|component|group|deploymentNode)\b/);
  if (decl && !inViews) {
    const path = [...stack.filter(Boolean), decl[1]].join(".");
    paths.add(path);
    leaves.add(decl[1]);
  }
  const opens = (raw.match(/\{/g) || []).length;
  const closes = (raw.match(/\}/g) || []).length;
  // 每遇到一个 { 就压入（声明行的 { 压它的名字，其它压 null 作占位）
  for (let i = 0; i < opens; i++) stack.push(decl ? decl[1] : null);
  for (let i = 0; i < closes; i++) {
    if (l.startsWith("views") || l.startsWith("model")) continue;
    stack.pop();
  }
}

// 3) 关系与视图端点必须指向已声明的标识符
const endpoints = [];
for (const m of text.matchAll(/^\s*([\w.]+)\s*(?:\.\.|->)\s*[\w.]+/gm)) endpoints.push(m[1]);
for (const m of text.matchAll(/^\s*([\w.]+)\s*(?:\.\.|->)\s*([\w.]+)/gm)) endpoints.push(m[2]);
const viewTargets = [...text.matchAll(/^\s*(?:systemContext|container|component)\s+([\w.]+)\s+"/gm)].map((m) => m[1]);
const unknown = endpoints.filter((id) => !paths.has(id) && !leaves.has(id) && !id.includes("."));
const unknownPaths = endpoints.filter((id) => id.includes(".") && !paths.has(id));

// 4) 位置参数上限
const argIssues = [];
for (const m of text.matchAll(/^\s*(\w+)\s*=\s*(component|container|softwareSystem|person)\s((?:"(?:[^"\\]|\\.)*"\s*)+)/gm)) {
  const n = [...m[3].matchAll(/"(?:[^"\\]|\\.)*"/g)].length;
  const max = { component: 4, container: 3, softwareSystem: 2, person: 2 }[m[2]];
  if (n > max) argIssues.push(`${m[1]} (${m[2]}) = ${n} 段，上限 ${max}`);
}
for (const m of text.matchAll(/^\s*[\w.]+\s*(?:->|\.\.)\s*[\w.]+\s+((?:"(?:[^"\\]|\\.)*"\s*)+)/gm)) {
  const n = [...m[1].matchAll(/"(?:[^"\\]|\\.)*"/g)].length;
  if (n > 2) argIssues.push(`一条关系写了 ${n} 个字符串参数，上限 2`);
}

console.log(`花括号：${depth === 0 && !firstBadLine ? "平衡" : `不平衡（depth=${depth}，首个多余 } 在行 ${firstBadLine}）`}`);
console.log(`声明元素：${paths.size} 个（含嵌套路径）`);
console.log(`关系端点：${endpoints.length} 处引用，简单名未知 ${new Set(unknown).size} 个，路径未知 ${new Set(unknownPaths).size} 个`);
if (unknownPaths.length) console.log("  未知路径 -> " + [...new Set(unknownPaths)].join(", "));
if (unknown.length) console.log("  未知简单名 -> " + [...new Set(unknown)].join(", "));
console.log(`视图：${viewTargets.length} 个 -> ${viewTargets.join(", ")}；未声明的 ${viewTargets.filter((t) => !paths.has(t)).length} 个`);
console.log(`位置参数超限：${argIssues.length} 处${argIssues.length ? " -> " + argIssues.join(" | ") : ""}`);
