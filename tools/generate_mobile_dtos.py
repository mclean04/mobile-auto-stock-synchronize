"""Generate only the frozen mobile-v1 DTO/codec layer. No runtime or network wiring.

Inputs are the pinned Backend revision-2 contract snapshots in docs/contracts/mobile-v1.
This emits concrete Kotlin types and checks, not a runtime schema interpreter.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / 'docs/contracts/mobile-v1'
OUTPUT = ROOT / 'app/src/main/java/com/example/finance_planning/network/mobile'
PINS = {
    'Backend-base-response.v1.schema.json': 'e8ee63752b4c2dfe3313f89905ad98ec2acd43b97823adeb38eb7eb2d1e6847c',
    'Backend-mobile-endpoints.v1.schema.json': '809571d7e7b78bb5c19daa1b4e4ae36cb793a3494f0ddcf2171896b6fa9e90a7',
    'Backend-mobile-endpoints.v1.bindings.json': '6a1a379040714d7a476ae1437732110c177bd25d45ae6d9d16c7bd5309d3ae0e',
}
docs = {}
for filename, digest in PINS.items():
    data = (INPUT / filename).read_bytes()
    assert hashlib.sha256(data).hexdigest() == digest, f'Contract changed: {filename}; bilateral review required'
    docs[filename] = json.loads(data)
schema_docs = [docs[n] for n in PINS if n.endswith('.schema.json')]
schemas = {}
for document in schema_docs:
    for name, schema in document['definitions'].items():
        assert name not in schemas
        schemas[name] = schema
schemas['ApiResponse'] = {'oneOf': schema_docs[0]['oneOf']}
bindings = docs['Backend-mobile-endpoints.v1.bindings.json']['endpoints']

def quote(value):
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')

def ref_name(schema):
    return schema['$ref'].rsplit('/', 1)[-1]

def field(name):
    return '`' + name + '`'

def title(value):
    return ''.join(x[:1].upper() + x[1:] for x in value.split('_'))

# Flatten only inline object/union shapes into named concrete Kotlin declarations.
models = {}
def kotlin_type(schema, hint):
    if '$ref' in schema:
        name = ref_name(schema)
        add_model(name, schemas[name])
        return name
    branches = schema.get('anyOf')
    if branches is None and any(x.get('type') == 'null' for x in schema.get('oneOf', [])):
        branches = schema['oneOf']
    if branches:
        non_null = [x for x in branches if x.get('type') != 'null']
        if len(non_null) == 1:
            return kotlin_type(non_null[0], hint) + ('?' if len(non_null) != len(branches) else '')
        assert all(x.get('type') in ('number', 'string', 'boolean') for x in non_null), hint
        return 'WireScalar' + ('?' if len(non_null) != len(branches) else '')
    kind = schema.get('type')
    if isinstance(kind, list):
        non_null = [x for x in kind if x != 'null']
        base = kotlin_type({'type': non_null[0]}, hint) if len(non_null) == 1 else 'WireScalar'
        return base + ('?' if 'null' in kind else '')
    if 'oneOf' in schema or kind == 'object':
        add_model(hint, schema)
        return hint
    if kind == 'array':
        return 'List<' + kotlin_type(schema['items'], hint + 'Item') + '>'
    if 'enum' in schema and kind is None:
        non_null = [x for x in schema['enum'] if x is not None]
        first = non_null[0]
        base = 'Boolean' if isinstance(first, bool) else 'Long' if isinstance(first, int) else 'String'
        return base + ('?' if None in schema['enum'] else '')
    return {'string': 'String', 'integer': 'Long', 'number': 'java.math.BigDecimal',
            'boolean': 'Boolean', 'null': 'Nothing?'}[kind]

def add_model(name, schema):
    if name in models:
        return
    models[name] = schema
    if 'oneOf' in schema:
        for i, branch in enumerate(schema['oneOf']):
            kotlin_type(branch, name + 'Variant' + str(i + 1))
    elif schema.get('type') == 'object':
        for key, prop in schema.get('properties', {}).items():
            kotlin_type(prop, name + title(key))

for name in ('Meta', 'ApiError', 'OperationOutcome', 'ValidationIssue', 'Pagination'):
    add_model(name, schemas[name])
for entry in bindings:
    request = schemas[ref_name(entry['request_schema'])]
    body = request['properties']['body']
    if '$ref' in body:
        kotlin_type(body, entry['operation'] + 'Body')
    response = schemas[ref_name(entry['responses']['200'])]
    if entry.get('envelope') is False:
        add_model(ref_name(entry['responses']['200']), response)
    else:
        kotlin_type(response['allOf'][1]['properties']['data'], entry['operation'] + 'Data')

def decode(schema, value, hint):
    typ = kotlin_type(schema, hint)
    if typ == 'Nothing?':
        return 'null'
    if typ.endswith('?'):
        non_null = dict(schema)
        if 'anyOf' in schema or 'oneOf' in schema:
            parts = [x for x in schema.get('anyOf', schema.get('oneOf')) if x.get('type') != 'null']
            non_null = parts[0] if len(parts) == 1 else {'anyOf': parts}
        elif isinstance(schema.get('type'), list):
            parts = [x for x in schema['type'] if x != 'null']
            non_null['type'] = parts[0] if len(parts) == 1 else parts
        elif 'enum' in schema:
            non_null['enum'] = [x for x in schema['enum'] if x is not None]
        return f'nullable({value}) {{ item -> {decode(non_null, "item", hint)} }}'
    if typ == 'String': return f'string({value})'
    if typ == 'Long': return f'integer({value})'
    if typ == 'Boolean': return f'boolean({value})'
    if typ == 'java.math.BigDecimal': return f'decimal({value})'
    if typ == 'WireScalar': return f'WireScalar.decode({value})'
    if typ.startswith('List<'):
        return f'array({value}).map {{ item -> {decode(schema["items"], "item", hint + "Item")} }}'
    return f'{typ}.decode(obj({value}))'

header = '// Generated by tools/generate_mobile_dtos.py from pinned revision 2. Do not hand edit.\n'
header += 'package com.example.finance_planning.network.mobile\n\nimport org.json.JSONObject\n'
output = [header]
for name, schema in list(models.items()):
    if 'oneOf' in schema:
        output.append(f'sealed interface {name} : MobileDto {{')
        for i, branch in enumerate(schema['oneOf']):
            typ = kotlin_type(branch, name + 'Variant' + str(i + 1))
            output.append(f'    data class Variant{i+1}(val value: {typ}) : {name} {{ override fun toJson() = value.toJson() }}')
        output.append(f'    companion object {{ fun decode(value: JSONObject): {name} {{')
        output.append(f'        ContractChecks.check({quote(name)}, value)')
        for i, branch in enumerate(schema['oneOf']):
            typ = kotlin_type(branch, name + 'Variant' + str(i + 1))
            output.append(f'        decodeOrNull {{ {typ}.decode(value) }}?.let {{ return Variant{i+1}(it) }}')
        output.append('        throw MobileContractViolation("Unsupported object variant")\n    } }\n}\n')
        continue
    props = schema.get('properties', {})
    if not props and isinstance(schema.get('additionalProperties'), dict):
        output.append(f'data class {name}(val values: Map<String, WireScalar?>) : MobileDto {{')
        output.append('    override fun toJson() = objectJson(values)')
        output.append(f'    companion object {{ fun decode(value: JSONObject): {name} {{')
        output.append(f'        ContractChecks.check({quote(name)}, value)')
        output.append(f'        return {name}(value.keys().asSequence().associateWith {{ nullable(value.get(it)) {{ v -> WireScalar.decode(v) }} }})')
        output.append('    } }\n}\n')
        continue
    req = set(schema.get('required', []))
    args = []
    for key, prop in props.items():
        typ = kotlin_type(prop, name + title(key))
        args.append(f'    val {field(key)}: {typ}' if key in req else f'    val {field(key)}: WireField<{typ}> = WireField.Absent')
    output.append(('data class ' if args else 'class ') + name + '(\n' + ',\n'.join(args) + '\n) : MobileDto {')
    output.append('    override fun toJson() = objectJson(mapOf(' + ', '.join(f'{quote(k)} to {field(k)}' for k in props) + '))')
    output.append(f'    companion object {{ fun decode(value: JSONObject): {name} {{')
    output.append(f'        ContractChecks.check({quote(name)}, value)')
    values = []
    for key, prop in props.items():
        decoded = decode(prop, f'value.get({quote(key)})', name + title(key))
        if key not in req:
            decoded = f'if (value.has({quote(key)})) WireField.Present({decoded}) else WireField.Absent'
        values.append('            ' + field(key) + ' = ' + decoded)
    output.append(f'        return {name}(\n' + ',\n'.join(values) + '\n        )\n    } }\n}\n')

# Emit checks as Kotlin code for this finite contract, including nested unions.
checks = []
def check_code(schema, value, indent='        '):
    lines = []
    def add(line): lines.append(indent + line)
    if '$ref' in schema:
        add(f'check({quote(ref_name(schema))}, {value})')
        return lines
    for keyword, expected in [('allOf', None), ('anyOf', '> 0'), ('oneOf', '== 1')]:
        if keyword in schema:
            branches = []
            for child in schema[keyword]:
                branches.append('matches {\n' + '\n'.join(check_code(child, value, indent + '    ')) + '\n' + indent + '}')
            if expected is None:
                for child in schema[keyword]: lines += check_code(child, value, indent)
            else:
                add('expect(listOf(' + ', '.join(branches) + ').count { it } ' + expected + ')')
    if 'not' in schema:
        add('expect(!matches {\n' + '\n'.join(check_code(schema['not'], value, indent + '    ')) + '\n' + indent + '})')
    if 'type' in schema:
        ts = schema['type'] if isinstance(schema['type'], list) else [schema['type']]
        add(f'expect(listOf({", ".join(quote(t) for t in ts)}).any {{ hasType({value}, it) }})')
    if 'enum' in schema:
        enum_json = quote(json.dumps(schema['enum'], separators=(',', ':')))
        add(f'expect(enumContains({enum_json}, {value}))')
    if 'properties' in schema or 'additionalProperties' in schema:
        add(f'if ({value} is JSONObject) {{')
        props = schema.get('properties', {})
        for key in schema.get('required', []): add(f'    expect({value}.has({quote(key)}))')
        for key, child in props.items():
            add(f'    if ({value}.has({quote(key)})) {{')
            add(f'        val child = clean({value}.get({quote(key)}))')
            lines += check_code(child, 'child', indent + '        ')
            add('    }')
        if schema.get('additionalProperties') is False:
            allowed = 'setOf<String>(' + ', '.join(quote(k) for k in props) + ')'
            add(f'    expect({value}.keys().asSequence().all {{ it in {allowed} }})')
        elif isinstance(schema.get('additionalProperties'), dict):
            add(f'    {value}.keys().asSequence().forEach {{ key ->')
            add(f'        val child = clean({value}.get(key))')
            lines += check_code(schema['additionalProperties'], 'child', indent + '        ')
            add('    }')
        if 'maxProperties' in schema: add(f'    expect({value}.length() <= {schema["maxProperties"]})')
        add('}')
    if 'items' in schema:
        add(f'if ({value} is org.json.JSONArray) {{')
        add(f'    for (i in 0 until {value}.length()) {{')
        add(f'        val child = clean({value}.get(i))')
        lines += check_code(schema['items'], 'child', indent + '        ')
        add('    }\n' + indent + '}')
    for k, op in [('minLength', '>='), ('maxLength', '<=')]:
        if k in schema: add(f'if ({value} is String) expect({value}.codePointCount(0, {value}.length) {op} {schema[k]})')
    for k, op in [('minItems', '>='), ('maxItems', '<=')]:
        if k in schema: add(f'if ({value} is org.json.JSONArray) expect({value}.length() {op} {schema[k]})')
    if schema.get('uniqueItems'): add(f'if ({value} is org.json.JSONArray) expect(array({value}).distinct().size == {value}.length())')
    for k, op in [('minimum', '>='), ('maximum', '<=')]:
        if k in schema:
            if k == 'minimum' and schema.get('exclusiveMinimum'): op = '>'
            add(f'if ({value} is Number) expect(decimal({value}) {op} java.math.BigDecimal({quote(str(schema[k]))}))')
    if 'pattern' in schema: add(f'if ({value} is String) expect(Regex({quote(schema["pattern"])}).containsMatchIn({value}))')
    if 'format' in schema: add(f'if ({value} is String) expect(validFormat({quote(schema["format"])}, {value}))')
    return lines

checks.append(header + '\ninternal object ContractChecks {\n    fun check(name: String, raw: Any?) {\n        val value = clean(raw)\n        when (name) {')
all_checks = dict(schemas)
all_checks.update(models)
for name in all_checks:
    checks.append(f'            {quote(name)} -> check{title(name)}(value)')
checks.append('            else -> throw MobileContractViolation("Unknown contract type")\n        }\n    }')
for name, schema in all_checks.items():
    checks.append(f'    private fun check{title(name)}(value: Any?) {{')
    checks += check_code(schema, 'value')
    checks.append('    }')
checks.append('}\n')

OUTPUT.mkdir(parents=True, exist_ok=True)
(OUTPUT / 'MobileDtos.kt').write_text('\n'.join(output))
(OUTPUT / 'ContractChecks.kt').write_text('\n'.join(checks))

service = [header, 'import retrofit2.Response\nimport retrofit2.http.*\n',
           '/** Inactive until an explicitly configured, approved client is injected. No dynamic URLs. */',
           'interface MobileBackendService {']
for entry in bindings:
    name = entry['operation']
    request = schemas[ref_name(entry['request_schema'])]['properties']
    params = []
    for key in request['path']['properties']:
        params.append(f'@Path({quote(key)}) {field(key)}: String')
    query = request['query']
    if '$ref' not in query:
        for key, prop in query.get('properties', {}).items():
            typ = 'Int' if prop.get('type') == 'integer' else 'String'
            params.append(f'@Query({quote(key)}) {field(key)}: {typ}? = null')
    if '$ref' in request['body']:
        params.append(f'@Body body: {ref_name(request["body"])}')
    if entry.get('auth') != 'none':
        params.append('@HeaderMap headers: Map<String, String>')
    response_name = ref_name(entry['responses']['200'])
    data_type = response_name if entry.get('envelope') is False else ref_name(schemas[response_name]['allOf'][1]['properties']['data'])
    result = data_type if entry.get('envelope') is False else f'MobileSuccess<{data_type}>'
    service.append(f'    @MobileOperation({quote(name)}) @{entry["method"]}({quote(entry["proposed_path"].lstrip("/"))})')
    service.append(f'    suspend fun {name}({", ".join(params)}): Response<{result}>\n')
service.append('}\n')
(OUTPUT / 'MobileBackendService.kt').write_text('\n'.join(service))

registry = [header, 'internal object MobileDtoCodecs {', '    fun decode(type: Class<*>, value: JSONObject): MobileDto = when (type) {']
for name in models:
    registry.append(f'        {name}::class.java -> {name}.decode(value)')
registry.append('        else -> throw MobileContractViolation("Unsupported DTO type")\n    }\n}\n')
(OUTPUT / 'MobileDtoCodecs.kt').write_text('\n'.join(registry))
print(f'Generated {len(models)} concrete DTO shapes and {len(all_checks)} contract checks; no transport wiring changed.')
