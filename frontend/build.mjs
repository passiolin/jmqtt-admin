import { build } from 'esbuild'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { baseOptions, createVuePlugins } from './vue-plugin.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.resolve(here, 'dist')

const styleWarnings = []

async function emitHtml() {
  const html = await fs.readFile(path.join(here, 'index.html'), 'utf8')
  // 入口与样式由本脚本产出, 因此在这里改写引用 —— index.html 保持「开发时可直接用
  // Vite 跑」的形态(指向 /src/main.js), 不必为生产单独维护一份
  const emitted = html
      .replace('<script type="module" src="/src/main.js"></script>',
          '<link rel="stylesheet" href="/assets/app.css">\n'
          + '<script type="module" src="/assets/app.js"></script>')
  await fs.writeFile(path.join(outDir, 'index.html'), emitted)
}

async function main() {
  await fs.rm(outDir, { recursive: true, force: true })
  await fs.mkdir(path.join(outDir, 'assets'), { recursive: true })

  await build({
    ...baseOptions(here),
    outdir: path.join(outDir, 'assets'),
    entryNames: 'app',
    assetNames: 'app',
    bundle: true,
    format: 'esm',
    platform: 'browser',
    target: ['es2020'],
    minify: true,
    logLevel: 'info',
    plugins: createVuePlugins(styleWarnings)
  })

  await emitHtml()

  for (const warning of styleWarnings) {
    console.warn('[warn] ' + warning)
  }
  const files = await fs.readdir(path.join(outDir, 'assets'))
  console.log('产物目录: ' + outDir)
  for (const file of files) {
    const stat = await fs.stat(path.join(outDir, 'assets', file))
    console.log(`  assets/${file}  ${(stat.size / 1024).toFixed(1)} KB`)
  }
}

main().catch((error) => {
  console.error('构建失败: ' + error.message)
  process.exit(1)
})
