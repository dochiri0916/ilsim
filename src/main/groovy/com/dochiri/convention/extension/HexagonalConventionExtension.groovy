package com.dochiri.convention.extension

class HexagonalConventionExtension {
    String domainPackageSegment = 'domain'
    String applicationPackageSegment = 'application'
    String infrastructurePackageSegment = 'infrastructure'
    String presentationPackageSegment = 'presentation'

    boolean enforceDomainEntitySeparation = true
    boolean enforceDomainStaticFactoryMethod = true
    boolean requireTableAnnotation = true
    List<String> entitySingularNameExceptions = []
    List<String> pluralTableNameExceptions = []
    List<String> domainStaticFactoryExceptions = []
}
