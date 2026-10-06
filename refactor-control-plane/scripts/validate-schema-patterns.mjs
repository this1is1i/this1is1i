import fs from 'node:fs';
// Standard JavaScript RegExp is intentionally independent of the Java server validator.
let count = 0;
function visit(value) {
  if (!value || typeof value !== 'object') return;
  if (typeof value.pattern === 'string') { new RegExp(value.pattern); count++; }
  for (const nested of Object.values(value)) visit(nested);
}
visit(JSON.parse(fs.readFileSync(process.argv[2], 'utf8')));
if (!count) throw new Error('No schema patterns checked');
console.log(`Validated ${count} schema patterns using JavaScript RegExp`);
