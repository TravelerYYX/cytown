const assert = require('node:assert/strict')
function enabled(expression, flags) {
  const tokens = expression.match(/[A-Z][A-Z0-9-]*|&&|\|\||!|\(|\)/g) || []
  assert.equal(tokens.join(''), expression.replace(/\s/g, ''), 'Invalid platform expression: ' + expression)
  let position = 0
  function atom() {
    const token = tokens[position++]
    if (token === '!') return !atom()
    if (token === '(') { const value = or(); assert.equal(tokens[position++], ')'); return value }
    assert(token && !['&&', '||', ')'].includes(token))
    return flags.has(token)
  }
  function and() { let value = atom(); while (tokens[position] === '&&') { position++; const right = atom(); value = value && right } return value }
  function or() { let value = and(); while (tokens[position] === '||') { position++; const right = and(); value = value || right } return value }
  const result = or(); assert.equal(position, tokens.length); return result
}
function platformSource(source, target) {
  const flags = new Set(target.startsWith('app-') ? ['APP', 'APP-' + target.slice(4).toUpperCase(), 'VUE3', 'VUE3-VAPOR'] : target === 'h5' ? ['H5', 'WEB', 'VUE3'] : ['MP-WEIXIN', 'VUE3'])
  const stack = [{ active: true }]
  const pattern = /\/\/[ \t]*#(ifdef|ifndef|else|endif)\b([^\r\n]*)|\/\*[ \t]*#(ifdef|ifndef|else|endif)\b([\s\S]*?)\*\/|<!--[ \t]*#(ifdef|ifndef|else|endif)\b([\s\S]*?)-->/g
  let output = '', cursor = 0
  for (const directive of source.matchAll(pattern)) {
    const segment = source.slice(cursor,directive.index)
    output += stack[stack.length - 1].active ? segment : segment.replace(/[^\r\n]/g,'')
    const kind = directive[1] || directive[3] || directive[5], condition = directive[2] || directive[4] || directive[6] || ''
    if (kind === 'endif') { assert(stack.length > 1, 'Unmatched endif'); stack.pop() }
    else if (kind === 'else') { assert(stack.length > 1, 'Unmatched else'); const frame = stack[stack.length - 1]; assert(!frame.hadElse); frame.hadElse = true; frame.active = frame.parent && !frame.branch }
    else { const parent = stack[stack.length - 1].active; const branch = kind === 'ifdef' ? enabled(condition.trim(), flags) : !enabled(condition.trim(), flags); stack.push({ active: parent && branch, parent, branch }) }
    output += directive[0].replace(/[^\r\n]/g,'')
    cursor = directive.index + directive[0].length
  }
  const suffix = source.slice(cursor)
  output += stack[stack.length - 1].active ? suffix : suffix.replace(/[^\r\n]/g,'')
  assert.equal(stack.length, 1, 'Unclosed platform condition')
  return output
}
module.exports = { platformSource }
