package no.skatteetaten.aurora.databasehotel.service

import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import kotlin.streams.toList
import mu.KotlinLogging
import no.skatteetaten.aurora.databasehotel.DatabaseEngine
import no.skatteetaten.aurora.databasehotel.domain.DatabaseSchema
import no.skatteetaten.aurora.databasehotel.service.internal.SchemaLabelMatcher.findAllMatchingSchemas
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

data class DatabaseInstanceRequirements(
    val databaseEngine: DatabaseEngine = DatabaseEngine.ORACLE,
    val instanceName: String? = null,
    val instanceLabels: Map<String, String> = emptyMap(),
    val instanceFallback: Boolean = true
)

data class TablespaceInfo(
    val max: Int,
    val used: Int
) {
    val available get(): Int = max - used
}

data class ConnectionVerification(
    val hasSucceeded: Boolean? = null,
    val message: String? = ""
)

@Service
class DatabaseHotelService(private val databaseHotelAdminService: DatabaseHotelAdminService) {

    fun findSchemaById(id: String, active: Boolean = true): Pair<DatabaseSchema, DatabaseInstance?>? {

        val candidates = mutableListOf<Pair<DatabaseSchema, DatabaseInstance?>>()

        databaseHotelAdminService.findAllDatabaseInstances()
            .parallelStream()
            .map { it.findSchemaById(id, active) to it }
            .filter { it.first != null }
            .map { it.first!! to it.second }
            .toList().also { candidates.addAll(it) }

        databaseHotelAdminService.externalSchemaManager?.findSchemaById(id)
            ?.let { it to null }
            ?.let { candidates.add(it) }

        verifyOnlyOneCandidate(id, candidates)
        return candidates.firstOrNull()
    }

    fun findAllDatabaseSchemas(
        engine: DatabaseEngine? = null,
        labelsToMatch: Map<String, String?> = emptyMap(),
        ignoreActiveFilter: Boolean = false
    ): Set<DatabaseSchema> {

        val schemas = databaseHotelAdminService.findAllDatabaseInstances(engine)
            .pFlatMap { it.findAllSchemas(labelsToMatch, ignoreActiveFilter) }

        val externalSchemas = databaseHotelAdminService.externalSchemaManager?.findAllSchemas() ?: emptySet()
        val matchingExternalSchemas = findAllMatchingSchemas(externalSchemas, labelsToMatch)
        return schemas + matchingExternalSchemas
    }

    fun getTablespaceInfo(): List<Pair<DatabaseInstance, TablespaceInfo>> {
        val databaseInstances = databaseHotelAdminService.findAllDatabaseInstances()
        return databaseInstances
            .mapNotNull { instance ->
                val maxTablespaces = instance.getMaxTablespaces()
                val usedTablespaces = instance.getUsedTablespaces()

                if (maxTablespaces == null || usedTablespaces == null) return@mapNotNull null

                val tablespaceInfo = TablespaceInfo(maxTablespaces, usedTablespaces)
                Pair(instance, tablespaceInfo)
            }
    }

    fun findAllInactiveDatabaseSchemas(labelsToMatch: Map<String, String?> = emptyMap()): Set<DatabaseSchema> =
        databaseHotelAdminService.findAllDatabaseInstances(null)
            .pFlatMap { it.findAllSchemas(labelsToMatch, true) }
            .filter { !it.active }
            .toSet()

    fun createSchema(requirements: DatabaseInstanceRequirements = DatabaseInstanceRequirements()): DatabaseSchema =
        createSchema(requirements, emptyMap())

    fun createSchema(
        requirements: DatabaseInstanceRequirements,
        labels: Map<String, String?> = emptyMap()
    ): DatabaseSchema {

        val databaseInstance = databaseHotelAdminService.findDatabaseInstanceOrFail(requirements)
        val schema = databaseInstance.createSchema(labels)

        logger.info("Created schema name={}, id={} with labels={}", schema.name, schema.id, schema.labels.toString())
        return schema
    }

    fun deactivateSchema(id: String, cooldownDuration: Duration?) {

        findSchemaById(id)?.let { (schema, databaseInstance) ->

            when (databaseInstance) {
                // TODO: Should ExternalSchemaManager put schemas into cooldown?
                null -> databaseHotelAdminService.externalSchemaManager?.deleteSchema(id)
                else -> databaseInstance.deactivateSchema(schema.name, cooldownDuration)
            }
        }
    }

    fun validateConnection(id: String) =
        findSchemaById(id)?.let { (schema, _) ->
            val user = schema.users.first()
            validateConnection(schema.jdbcUrl, user.name, user.password)
        } ?: throw IllegalArgumentException("no database schema found for id: $id")

    fun validateConnection(jdbcUrl: String, username: String, password: String) =
        try {
            DriverManager.getConnection(jdbcUrl, username, password).use {
                ConnectionVerification(true, "successful")
            }
        } catch (ex: SQLException) {
            ConnectionVerification(false, ex.message)
        }

    @JvmOverloads
    fun updateSchema(
        id: String,
        labels: Map<String, String?>,
        username: String? = null,
        jdbcUrl: String? = null,
        password: String? = null
    ): DatabaseSchema {

        logger.info("Updating labels for schema with id={} to labels={}", id, labels)

        val (schema, databaseInstance) = findSchemaById(id)
            ?: throw DatabaseServiceException("No such schema $id")

        return if (databaseInstance != null) {
            databaseInstance.replaceLabels(schema, labels)
            schema
        } else {
            databaseHotelAdminService.externalSchemaManager?.updateSchema(schema, labels, username, password)
                ?: throw IllegalStateException("Unable to update schema $id - no ExternalSchemaManager registered")
        }
    }

    fun registerExternalSchema(
        username: String,
        password: String,
        jdbcUrl: String,
        labels: Map<String, String?>
    ): DatabaseSchema {
        val externalSchemaManager = databaseHotelAdminService.externalSchemaManager
            ?: throw DatabaseServiceException("External Schema Manager has not been registered")
        return externalSchemaManager.registerSchema(username, password, jdbcUrl, labels)
    }

    fun Set<DatabaseInstance>.pFlatMap(func: (t: DatabaseInstance) -> Set<DatabaseSchema>): Set<DatabaseSchema> =
        this.parallelStream().flatMap {
            logger.debug("Fetching schemas for instance ${it.metaInfo.host}")
            val (timeSpent, res) = measureTimeMillis { func(it) }
            logger.debug("Fetched ${res.size} schemas for instance ${it.metaInfo.host} in $timeSpent millis")
            res.stream()
        }.toList().toSet()

    companion object {

        private fun verifyOnlyOneCandidate(
            id: String,
            candidates: List<Pair<DatabaseSchema, DatabaseInstance?>>
        ) {
            if (candidates.size <= 1) return

            candidates.joinToString(", ") { (schema, instance) ->
                val host = instance?.metaInfo?.host
                "[schemaName=${schema.name}, jdbcUrl=${schema.jdbcUrl}, hostName=$host]"
            }
                .takeIf { it.isNotEmpty() }
                ?.run { throw IllegalStateException("More than one schema from different database servers matched the specified id [$id]: $this") }
        }
    }
}
