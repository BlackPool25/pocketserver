package com.pocketserver.hub.alpine

/**
 * Transitive shared library (.so) and package dependency resolver for Alpine Linux.
 *
 * Traverses soname references (`so:libssl.so.3`), virtual provider tags (`p:so:...`),
 * and package version constraints to compute the complete hermetic dependency closure.
 */
class ApkDependencyResolver(
    packages: List<AlpinePackageRecord>
) {

    private val packageMap = mutableMapOf<String, AlpinePackageRecord>()
    private val providerMap = mutableMapOf<String, String>() // soname/command -> packageName

    init {
        for (pkg in packages) {
            packageMap[pkg.packageName] = pkg

            // Map provides (e.g. "so:libssl.so.3=3" -> "openssl", "cmd:ffmpeg" -> "ffmpeg")
            for (prov in pkg.provides) {
                val cleanProv = prov.substringBefore("=").substringBefore("<").substringBefore(">")
                providerMap[cleanProv] = pkg.packageName
            }
        }
    }

    data class ResolutionResult(
        val isResolved: Boolean,
        val targetPackage: String,
        val orderedInstallPlan: List<AlpinePackageRecord>,
        val missingDependencies: List<String> = emptyList()
    )

    /**
     * Resolves the full transitive dependency closure for the requested package.
     */
    fun resolve(packageName: String): ResolutionResult {
        val rootPkg = packageMap[packageName]
        if (rootPkg == null) {
            return ResolutionResult(
                isResolved = false,
                targetPackage = packageName,
                orderedInstallPlan = emptyList(),
                missingDependencies = listOf(packageName)
            )
        }

        val visited = mutableSetOf<String>()
        val installPlan = mutableListOf<AlpinePackageRecord>()
        val missing = mutableListOf<String>()

        fun dfs(pkg: AlpinePackageRecord) {
            if (visited.contains(pkg.packageName)) return
            visited.add(pkg.packageName)

            for (rawDep in pkg.dependencies) {
                val cleanDep = rawDep.substringBefore(">=").substringBefore("<=").substringBefore("=")

                // Ignore standard musl libc runtime provided by base sysroot
                if (cleanDep.startsWith("so:libc.musl") || cleanDep.startsWith("so:ld-musl")) {
                    continue
                }

                val providerPkgName = if (packageMap.containsKey(cleanDep)) {
                    cleanDep
                } else {
                    providerMap[cleanDep]
                }

                if (providerPkgName != null) {
                    val depPkg = packageMap[providerPkgName]
                    if (depPkg != null && !visited.contains(providerPkgName)) {
                        dfs(depPkg)
                    }
                } else {
                    missing.add(rawDep)
                }
            }

            installPlan.add(pkg)
        }

        dfs(rootPkg)

        return ResolutionResult(
            isResolved = missing.isEmpty(),
            targetPackage = packageName,
            orderedInstallPlan = installPlan,
            missingDependencies = missing
        )
    }
}
