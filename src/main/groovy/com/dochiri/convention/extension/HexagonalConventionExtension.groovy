package com.dochiri.convention.extension

class HexagonalConventionExtension {
    String basePackage
    String domainPackageSegment = 'domain'
    String applicationPackageSegment = 'application'
    String infrastructurePackageSegment = 'adapter.out'
    String presentationPackageSegment = 'adapter.in.web'

    boolean enforceDomainEntitySeparation = true
    boolean enforceDomainStaticFactoryMethod = true
    boolean enforceContextExceptionConsolidation = true
    Set<String> exceptionTypeSplitAllowlist = [] as Set
    boolean enforceCrossContextIdentifierIsolation = true
    Set<String> publishedLanguagePackagePrefixes = [] as Set
    boolean requireTableAnnotation = true

    BigDecimal overallLineCoverageMinimum = 0.85
    BigDecimal overallBranchCoverageMinimum = 0.80
    BigDecimal domainLineCoverageMinimum = 0.95
    BigDecimal domainBranchCoverageMinimum = 0.90
    BigDecimal applicationLineCoverageMinimum = 0.90
    BigDecimal applicationBranchCoverageMinimum = 0.85
    BigDecimal infrastructureLineCoverageMinimum = 0.80
    BigDecimal infrastructureBranchCoverageMinimum = 0.70
    BigDecimal inboundAdapterLineCoverageMinimum = 0.80
    BigDecimal inboundAdapterBranchCoverageMinimum = 0.70
    BigDecimal changedLineCoverageMinimum = 0.90
    BigDecimal changedBranchCoverageMinimum = 0.85

    boolean enforcePitOnCheck = false
    boolean enforceMsaWebAdapterBoundary = false
    int mutationScoreMinimum = 80
    int testStrengthMinimum = 85
}
