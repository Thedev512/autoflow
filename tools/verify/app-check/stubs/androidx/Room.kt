// Mirrors androidx.room 2.6.x annotations and builder.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.room

import android.content.Context
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [],
    val ignoredColumns: Array<String> = [],
)

annotation class Index(vararg val value: String, val name: String = "", val unique: Boolean = false)

@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

@Target(AnnotationTarget.CLASS)
annotation class Dao

@Target(AnnotationTarget.FUNCTION)
annotation class Query(val value: String)

@Target(AnnotationTarget.FUNCTION)
annotation class Insert(val entity: KClass<*> = Any::class, val onConflict: Int = 3)

@Target(AnnotationTarget.FUNCTION)
annotation class Upsert(val entity: KClass<*> = Any::class)

@Target(AnnotationTarget.CLASS)
annotation class Database(
    val entities: Array<KClass<*>> = [],
    val views: Array<KClass<*>> = [],
    val version: Int,
    val exportSchema: Boolean = true,
)

abstract class RoomDatabase {
    open class Builder<T : RoomDatabase> {
        fun fallbackToDestructiveMigration(): Builder<T> = this
        fun build(): T = TODO()
    }
}

object Room {
    @JvmStatic
    fun <T : RoomDatabase> databaseBuilder(context: Context, klass: Class<T>, name: String?): RoomDatabase.Builder<T> = TODO()
}
