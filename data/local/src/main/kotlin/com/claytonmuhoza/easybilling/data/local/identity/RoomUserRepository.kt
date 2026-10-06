package com.claytonmuhoza.easybilling.data.local.identity

import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository

/** [UserRepository] backed by the local database. */
internal class RoomUserRepository(private val dao: UserDao) : UserRepository {

    override suspend fun count(): Int = dao.count()

    override suspend fun findAll(): List<User> = dao.findAll().map { it.toDomain() }

    override suspend fun find(id: UserId): User? = dao.find(id.value)?.toDomain()

    override suspend fun create(newUser: NewUser): User {
        val id = dao.insert(newUser.toEntity())
        return User(UserId(id), newUser.name, newUser.roles, newUser.credentials, pinTemporary = newUser.pinTemporary)
    }

    override suspend fun update(user: User) = dao.update(user.toEntity())
}
