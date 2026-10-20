// 把本目录的 .dot 渲染成同名 .svg，并在写文件之前先解析 + 布局一遍（status != success = 图本身有错）。
// 依赖：@viz-js/viz（Graphviz 16.1.0 的 WASM 构建）—— 本机没有 graphviz / java 时用它顶上。
//   npm install @viz-js/viz
//   node render-svg.mjs                        # 渲染全部 .dot
//   node render-svg.mjs compose-pipeline.dot   # 只渲染一个
// 同时打印节点/边计数，供 docs/architecture/README.md 的「渲染验证状态」一节核对。
// 注意：WASM 构建没有 CJK 字体度量，按估算字宽画框，中文标签会略微越过自己的圆角边框。
// 要出终稿请用带 CJK 字体的本机 graphviz 重渲染：dot -Tsvg x.dot -o x.svg
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, basename } from "node:path";
import { instance } from "@viz-js/viz";

const dir = import.meta.dirname;
const args = process.argv.slice(2);
const files = (args.length ? args : readdirSync(dir).filter((f) => f.endsWith(".dot"))).map((f) => join(dir, f));

const viz = await instance();
let bad = 0;
for (const file of files) {
  const src = readFileSync(file, "utf8");
  const name = basename(file);
  const svg = viz.render(src, { format: "svg" });
  if (svg.status !== "success") {
    bad++;
    console.error(`FAIL ${name}: ${svg.errors.map((e) => e.text ?? String(e)).join(" | ") || svg.status}`);
    continue;
  }
  const out = join(dir, name.replace(/\.dot$/, ".svg"));
  writeFileSync(out, svg.output);
  const count = (cls) => (svg.output.match(new RegExp(`class="${cls}"`, "g")) ?? []).length;
  const size = (svg.output.match(/width="([\d.]+)pt" height="([\d.]+)pt"/) ?? []).slice(1).join("x");
  console.log(`ok   ${name} -> ${basename(out)}  节点=${count("node")} 边=${count("edge")} 边界框=${count("cluster")} 画布=${size}pt`);
}
process.exit(bad ? 1 : 0);
