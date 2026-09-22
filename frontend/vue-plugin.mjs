/*
 * Copyright (c) 2026 ipuff.online
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
/**
 * esbuild 的 Vue 单文件组件插件。
 *
 * 抽到单独文件是为了让**构建**与**渲染冒烟测试**共用同一份编译逻辑 ——
 * 若两边各写一份, 测试通过就只能说明「测试那套配置能跑」,
 * 而真正发布的那套仍然可能有问题。
 */
import { parse, compileScript, compileStyle } from '@vue/compiler-sfc'
import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'

const STYLE_NS = 'vue-style'

/**
 * @param styleWarnings 收集 <style scoped> 之类的告警(由调用方决定如何呈现)
 */
export function createVuePlugins(styleWarnings = []) {
  const stylePlugin = {
    name: 'vue-style',
    setup(api) {
      api.onResolve({ filter: /\?vue-style$/ }, (args) => ({
        path: args.path,
        namespace: STYLE_NS
      }))
      api.onLoad({ filter: /.*/, namespace: STYLE_NS }, async (args) => {
        const file = args.path.replace(/\?vue-style$/, '')
        const { descriptor } = parse(await fs.readFile(file, 'utf8'), { filename: file })
        const chunks = []
        const id = 'data-v-' + createHash('sha256').update(file).digest('hex').slice(0, 8)
        for (const block of descriptor.styles || []) {
          if (block.scoped) {
            styleWarnings.push(path.basename(file) + ' 含 <style scoped>, 本路径不支持, 已按全局样式输出')
          }
          const result = await compileStyle({ source: block.content, filename: file, id })
          chunks.push(result.code)
        }
        return { contents: chunks.join('\n'), loader: 'css' }
      })
    }
  }

  const vuePlugin = {
    name: 'vue-sfc',
    setup(api) {
      api.onLoad({ filter: /\.vue$/ }, async (args) => {
        const source = await fs.readFile(args.path, 'utf8')
        const { descriptor, errors } = parse(source, { filename: args.path })
        if (errors.length) {
          throw new Error(args.path + ' 解析失败: ' + errors.map((e) => e.message).join('; '))
        }
        if (!descriptor.scriptSetup && !descriptor.script) {
          throw new Error(args.path + ' 缺少 <script> 或 <script setup>')
        }
        const id = createHash('sha256').update(args.path).digest('hex').slice(0, 8)
        // inlineTemplate: 模板直接编译进同一个模块, 因此不需要再单独处理 render 函数,
        // 也就不需要维护「模板模块与脚本模块如何互相引用」那套约定
        const script = compileScript(descriptor, { id, inlineTemplate: true })

        let contents = script.content
        if (descriptor.styles && descriptor.styles.length) {
          contents = `import ${JSON.stringify(args.path + '?vue-style')}\n` + contents
        }
        return { contents, loader: 'js', resolveDir: path.dirname(args.path) }
      })
    }
  }

  return [vuePlugin, stylePlugin]
}

/**
 * esbuild 的通用选项。
 *
 * <p>Vue 的 bundler 构建把几个特性开关留作<b>编译期常量</b>。少了 define 不会编译失败,
 * 而是运行时抛 ReferenceError —— 一个更难定位的失败点。
 */
export function baseOptions(rootDir) {
  return {
    entryPoints: [path.join(rootDir, 'src/main.js')],
    bundle: true,
    platform: 'browser',
    target: ['es2020'],
    logLevel: 'silent',
    define: {
      __VUE_OPTIONS_API__: 'true',
      __VUE_PROD_DEVTOOLS__: 'false',
      __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false',
      // ★ 必须有这一行。Vue 的 bundler 构建里到处是
      // `if (process.env.NODE_ENV !== 'production')` 形式的开发期检查,
      // 而 esbuild 不像 Vite 那样自动注入它 —— 浏览器里 `process` 不存在,
      // 于是应用一加载就 ReferenceError。构建成功、页面全白, 是最难查的一类问题
      'process.env.NODE_ENV': '"production"'
    }
  }
}
