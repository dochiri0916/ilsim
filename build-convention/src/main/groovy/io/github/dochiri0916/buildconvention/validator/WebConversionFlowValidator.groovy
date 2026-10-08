package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.support.JavaLexicalScanner
import io.github.dochiri0916.buildconvention.support.SourceTypeIndex
import org.gradle.api.Project

/** Require existing application contracts to cross the web boundary through DTO conversions or a web Mapper. */
final class WebConversionFlowValidator {
    static List<String> validate(Project project, SourceTypeIndex index) {
        List<String> violations = []
        Map<String, Set<String>> associations = controllerContracts(index)
        index.entries.values().findAll { it.inspection.packageName.contains('.adapter.in.web') }.each { entry ->
            def type = entry.type
            String path = project.relativePath(entry.inspection.file)
            boolean request = type.simpleName.endsWith('Request')
            boolean response = type.simpleName.endsWith('Response')
            if (type.simpleName.endsWith('Controller') || type.annotation('RestController') != null) {
                validateControllerCalls(path, entry, index, violations)
            }
            if (!request && !response) return
            String prefix = entry.inspection.packageName.substring(0, entry.inspection.packageName.indexOf('.adapter.'))
            String stem = type.simpleName.replaceFirst(/(?:Request|Response)$/, '')
            List<Map> contracts = index.entries.values().findAll { candidate ->
                boolean category = request ? candidate.inspection.packageName in [prefix + '.application.port.in.command', prefix + '.application.port.in.query'] :
                        candidate.inspection.packageName == prefix + '.application.port.in.result'
                category && (candidate.type.simpleName in [stem + 'Command', stem + 'Query', stem + 'Result']
                        || associations[entry.name]?.contains(candidate.name)
                        || (request && entry.inspection.imports.contains(candidate.name) && candidate.type.simpleName ==~ /.*(?:Command|Query)/)
                        || type.memberTypes.any { index.resolve(it, entry) == candidate.name }
                        || type.methods.any { method -> method.modifiers.contains('PUBLIC') &&
                            (index.resolve(method.returnType, entry) == candidate.name
                            || method.parameterTypes.any { index.resolve(it, entry) == candidate.name }) })
            }
            contracts.each { contract ->
                String expected = response ? 'from' : contract.type.simpleName.endsWith('Query') ? 'toQuery' : 'toCommand'
                boolean direct = type.methods.any { method ->
                    method.name == expected && method.modifiers.contains('PUBLIC') && method.hasBody
                            && (request ? !method.modifiers.contains('STATIC') && index.resolve(method.returnType, entry) == contract.name :
                            method.modifiers.contains('STATIC') && index.resolve(method.returnType, entry) == entry.name
                                    && method.parameterTypes.size() == 1 && index.resolve(method.parameterTypes.first(), entry) == contract.name)
                }
                boolean mapped = index.entries.values().any { mapper ->
                    mapper.inspection.packageName.startsWith(prefix + '.adapter.in.web') && mapper.type.simpleName.endsWith('Mapper')
                            && mapper.type.methods.any { method ->
                        method.modifiers.contains('PUBLIC') && method.hasBody
                                && index.resolve(method.returnType, mapper) == (request ? contract.name : entry.name)
                                && method.parameterTypes.any { index.resolve(it, mapper) == (request ? entry.name : contract.name) }
                    }
                }
                if (!direct && !mapped) {
                    violations.add("${path} web adapter DTO '${type.simpleName}' with application contract '${contract.type.simpleName}' must provide ${expected} or a separate web Mapper conversion")
                }
            }
            type.methods.each { method ->
                String body = JavaLexicalScanner.codeOnly(method.codeBody)
                Map<String, String> receivers = type.fields.collectEntries { [(it.name): index.resolve(it.type, entry)] }
                method.parameterTypes.eachWithIndex { parameter, int position -> receivers[method.parameterNames[position]] = index.resolve(parameter, entry) }
                method.invocations.each { call ->
                    String receiver = call.selector.replaceFirst(/^this\./, '').tokenize('.').first()
                    String target = receivers[receiver] ?: index.resolve(receiver, entry)
                    if (target.contains('.application.service.') || target.contains('.application.port.in.usecase.')
                            || target.contains('.application.port.out.') || target.contains('.adapter.out.')) {
                        violations.add("${path} web adapter DTO conversion must not call services, UseCases, outbound ports or storage")
                    }
                }
                if (method.name in ['toCommand', 'toQuery', 'from'] && contracts && method.controlDepth > 0
                        && !(body =~ /\b[A-Z][\w]*Mapper\s*\./).find()) {
                    violations.add("${path} web adapter DTO conversion with business branches must delegate to a separate web Mapper")
                }
            }
        }
        return violations.unique()
    }

    private static Map<String, Set<String>> controllerContracts(SourceTypeIndex index) {
        Map<String, Set<String>> contracts = [:].withDefault { [] as Set }
        index.entries.values().findAll { it.type.simpleName.endsWith('Controller') || it.type.annotation('RestController') != null }.each { controller ->
            Map receivers = controller.type.fields.collectEntries { [(it.name): index.resolve(it.type, controller)] }
            controller.type.methods.each { method ->
                List<Map> requests = method.parameterTypes.collect { index.entries[index.resolve(it, controller)] }
                        .findAll { it?.type?.simpleName?.endsWith('Request') }
                List<Map> responses = index.entries.values().findAll { candidate ->
                    candidate.inspection.packageName.contains('.adapter.in.web') && candidate.type.simpleName.endsWith('Response')
                            && method.returnType?.tokenize('<>, .[]')?.contains(candidate.type.simpleName)
                            && (candidate.inspection.packageName == controller.inspection.packageName
                            || controller.inspection.imports.contains(candidate.name) || method.returnType.contains(candidate.name))
                }
                method.invocations.each { call ->
                    List<String> selector = call.selector.replaceFirst(/^this\./, '').tokenize('.')
                    if (selector.size() != 2) return
                    Map useCase = index.entries[receivers[selector.first()]]
                    if (!useCase?.inspection?.packageName?.contains('.application.port.in.usecase')) return
                    index.hierarchy(useCase).each { owner ->
                        owner.type.methods.findAll { it.name == selector.last() && it.parameterTypes.size() == call.arguments.size() }.each { contract ->
                            contract.parameterTypes.each { syntax ->
                                String typeName = index.resolve(syntax, owner)
                                if (typeName.contains('.application.port.in.command.') || typeName.contains('.application.port.in.query.')) {
                                    requests.each { contracts[it.name].add(typeName) }
                                }
                            }
                            String result = index.resolve(contract.returnType, owner)
                            if (result.contains('.application.port.in.result.')) responses.each { contracts[it.name].add(result) }
                        }
                    }
                }
            }
        }
        return contracts
    }

    private static void validateControllerCalls(String path, Map entry, SourceTypeIndex index, List<String> violations) {
        entry.type.methods.findAll { !it.constructor }.each { method ->
            Map<String, Map> requests = [:]
            method.parameterTypes.eachWithIndex { syntax, int position ->
                Map dto = index.entries[index.resolve(syntax, entry)]
                if (dto?.type?.simpleName?.endsWith('Request')) requests[method.parameterNames[position]] = dto
            }
            if (requests.isEmpty()) return
            Map receivers = entry.type.fields.collectEntries { [(it.name): index.resolve(it.type, entry)] }
            Map assignments = [:]
            def locals = JavaLexicalScanner.codeOnly(method.codeBody) =~ /\b(?:final\s+)?[\w.<>?,]+\s+([\w]+)\s*=\s*([^;]+);/
            while (locals.find()) assignments[locals.group(1)] = locals.group(2).trim()
            method.invocations.each { call ->
                List<String> selector = call.selector.replaceFirst(/^this\./, '').tokenize('.')
                if (selector.size() != 2) return
                Map useCase = index.entries[receivers[selector.first()]]
                if (!useCase?.inspection?.packageName?.contains('.application.port.in.usecase')) return
                index.hierarchy(useCase).each { owner ->
                    owner.type.methods.findAll { it.name == selector.last() && it.parameterTypes.size() == call.arguments.size() }.each { contract ->
                        contract.parameterTypes.eachWithIndex { syntax, int position ->
                            String expected = index.resolve(syntax, owner)
                            if (!expected.contains('.application.port.in.command.') && !expected.contains('.application.port.in.query.')) return
                            if (!convertedArgument(call.arguments[position], expected, requests, assignments, entry, index, [] as Set)) {
                                violations.add("${path} web adapter controller must pass Request conversion or a web Mapper result to inbound contract '${syntax}'")
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean convertedArgument(String expression, String expected, Map requests, Map assignments,
                                             Map entry, SourceTypeIndex index, Set visited) {
        String value = expression.trim()
        if (assignments.containsKey(value)) {
            return visited.add(value) && convertedArgument(assignments[value], expected, requests, assignments, entry, index, visited)
        }
        def direct = value =~ /(?s)^([\w]+)\.(toCommand|toQuery)\s*\(.*\)$/
        if (direct.matches() && requests.containsKey(direct.group(1))) {
            Map dto = requests[direct.group(1)]
            return dto != null && dto.type.methods.any { it.name == direct.group(2) && index.resolve(it.returnType, dto) == expected }
        }
        def mapped = value =~ /(?s)^([\w.]+Mapper)\.([\w]+)\s*\((.*)\)$/
        if (!mapped.matches()) return false
        Map mapper = index.entries[index.resolve(mapped.group(1), entry)]
        if (!mapper?.inspection?.packageName?.contains('.adapter.in.web')) return false
        List<String> arguments = mapped.group(3).split(',').collect { it.trim() }
        return mapper.type.methods.any { conversion -> conversion.name == mapped.group(2)
                && conversion.modifiers.contains('PUBLIC') && conversion.modifiers.contains('STATIC')
                && index.resolve(conversion.returnType, mapper) == expected
                && conversion.parameterTypes.size() == arguments.size()
                && arguments.withIndex().any { argument, int position ->
                    requests[argument]?.name == index.resolve(conversion.parameterTypes[position], mapper)
                } }
    }
}
