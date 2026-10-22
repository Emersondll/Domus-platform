// Gera public/licenses.txt de um workspace: licença do projeto e, para cada dependência de PRODUÇÃO
// (fecho transitivo), nome, versão, licença e o texto do arquivo de licença do pacote.
// As licenças MIT/ISC/BSD exigem que o aviso acompanhe o que é distribuído; o bundle do Vite pode
// descartar os comentários legais, então o aviso é servido como arquivo próprio (/licenses.txt).
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..", "..");
const workspace = resolve(process.argv[2] ?? process.cwd());
const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));

function findPackageDir(name, fromDir) {
  for (let dir = fromDir; ; dir = dirname(dir)) {
    const candidate = join(dir, "node_modules", name);
    if (existsSync(join(candidate, "package.json"))) return candidate;
    if (dirname(dir) === dir) return null;
  }
}

const seen = new Map();
(function walk(pkgDir, isRoot) {
  const pkg = readJson(join(pkgDir, "package.json"));
  const deps = { ...pkg.dependencies, ...(isRoot ? {} : pkg.optionalDependencies) };
  for (const name of Object.keys(deps)) {
    const dir = findPackageDir(name, pkgDir);
    if (!dir || seen.has(name)) continue;
    seen.set(name, dir);
    walk(dir, false);
  }
})(workspace, true);

const licenseFile = (dir) =>
  readdirSync(dir).find((f) => /^(licen[sc]e|copying|notice)(\..*)?$/i.test(f));

const parts = [
  "AVISOS DE SOFTWARE DE TERCEIROS",
  "O código próprio deste produto é licenciado sob a Licença MIT (abaixo). Os componentes a seguir mantêm as licenças originais.",
  "",
  "=== Projeto ===",
  readFileSync(join(root, "LICENSE"), "utf8").trim(),
];
for (const [name, dir] of [...seen].sort(([a], [b]) => a.localeCompare(b))) {
  const pkg = readJson(join(dir, "package.json"));
  const file = licenseFile(dir);
  parts.push("", `=== ${name}@${pkg.version} (${pkg.license ?? "licença não declarada"}) ===`);
  parts.push(file ? readFileSync(join(dir, file), "utf8").trim() : "(o pacote não traz arquivo de licença; ver o campo license do package.json)");
}

mkdirSync(join(workspace, "public"), { recursive: true });
writeFileSync(join(workspace, "public", "licenses.txt"), parts.join("\n") + "\n");
console.log(`licenses.txt: ${seen.size} dependências de produção`);
