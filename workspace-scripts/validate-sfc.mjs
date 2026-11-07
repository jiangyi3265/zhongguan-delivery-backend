import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../三端配送小程序/package.json', import.meta.url))
const { parse, compileScript, compileStyle, compileTemplate } = require('@vue/compiler-sfc')

const workspace = path.resolve(process.cwd())
const appRoot = path.join(workspace, '三端配送小程序')
const failures = []
const vueFiles = []

function walk(directory) {
	for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
		const absolute = path.join(directory, entry.name)
		if (entry.isDirectory() && !['node_modules', 'dist'].includes(entry.name)) walk(absolute)
		else if (entry.name.endsWith('.vue')) vueFiles.push(absolute)
	}
}

walk(appRoot)

for (const filename of vueFiles) {
	const source = fs.readFileSync(filename, 'utf8')
	const label = path.relative(appRoot, filename)
	const { descriptor, errors } = parse(source, { filename })
	for (const error of errors) failures.push(`${label}: SFC parse: ${String(error)}`)

	if (descriptor.script || descriptor.scriptSetup) {
		try { compileScript(descriptor, { id: `qc-${label}` }) }
		catch (error) { failures.push(`${label}: script: ${error.message}`) }
	}

	if (descriptor.template) {
		const result = compileTemplate({ source: descriptor.template.content, filename, id: `qc-${label}` })
		for (const error of result.errors) failures.push(`${label}: template: ${String(error)}`)
	}

	for (const style of descriptor.styles) {
		const result = compileStyle({ source: style.content, filename, id: `qc-${label}`, scoped: style.scoped })
		for (const error of result.errors) failures.push(`${label}: style: ${String(error)}`)
	}

	for (const match of source.matchAll(/from\s+['"]([^'"]+)['"]/g)) {
		if (!match[1].startsWith('.')) continue
		const target = path.resolve(path.dirname(filename), match[1])
		if (!fs.existsSync(target)) failures.push(`${label}: missing import ${match[1]}`)
	}
}

const pagesConfig = JSON.parse(fs.readFileSync(path.join(appRoot, 'pages.json'), 'utf8'))
const registered = new Set(pagesConfig.pages.map(page => page.path))
for (const page of registered) {
	if (!fs.existsSync(path.join(appRoot, `${page}.vue`))) failures.push(`pages.json: missing ${page}.vue`)
}

for (const filename of vueFiles) {
	const source = fs.readFileSync(filename, 'utf8')
	for (const match of source.matchAll(/\/pages\/[A-Za-z0-9_/-]+/g)) {
		const route = match[0].slice(1)
		if (!registered.has(route)) failures.push(`${path.relative(appRoot, filename)}: unregistered route ${match[0]}`)
	}
}

const buyerSources = vueFiles.filter(file => file.includes(`${path.sep}buyer${path.sep}`)).map(file => fs.readFileSync(file, 'utf8')).join('\n')
if (/prices\.(business|consumer)|prices\[['"](?:business|consumer)['"]\]/.test(buyerSources)) failures.push('buyer pages bypass the role-aware price selector')

if (failures.length) {
	console.error(failures.join('\n'))
	process.exit(1)
}

console.log(`Validated ${vueFiles.length} Vue SFC files and ${registered.size} registered pages.`)
