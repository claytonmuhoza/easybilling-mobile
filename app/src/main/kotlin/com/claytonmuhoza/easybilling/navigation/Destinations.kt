package com.claytonmuhoza.easybilling.navigation

import kotlinx.serialization.Serializable

/** First launch assistant: creation of the administrator account. */
@Serializable
data object SetupDestination

/** Login by PIN. */
@Serializable
data object LoginDestination

/** Choice of a new PIN with the recovery code. */
@Serializable
data object ResetPinDestination

/** Home screen, the start of the application once logged in. */
@Serializable
data object HomeDestination
