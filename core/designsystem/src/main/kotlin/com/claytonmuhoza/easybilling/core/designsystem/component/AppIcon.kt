package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.claytonmuhoza.easybilling.core.designsystem.R

/**
 * The icons of the application. Only the icons that are used are packaged, as vector drawables.
 *
 * @property drawable the vector drawable of the icon.
 */
enum class AppIcon(@DrawableRes internal val drawable: Int) {
    /** Adds something: an item to the sale, one more unit. */
    ADD(R.drawable.ds_ic_add),

    /** Takes one off. */
    REMOVE(R.drawable.ds_ic_remove),

    /** Closes a screen without keeping anything. */
    CLOSE(R.drawable.ds_ic_close),

    /** Done and acknowledged. */
    CHECK_CIRCLE(R.drawable.ds_ic_check_circle),

    /** Waiting. */
    SCHEDULE(R.drawable.ds_ic_schedule),

    /** Refused or failed. */
    ERROR(R.drawable.ds_ic_cancel),

    /** No longer active, or not allowed. */
    BLOCK(R.drawable.ds_ic_block),

    /** A search. */
    SEARCH(R.drawable.ds_ic_search),

    /** Opens what the row is about. */
    CHEVRON_RIGHT(R.drawable.ds_ic_chevron_right),

    /** Goes to the next step. */
    ARROW_FORWARD(R.drawable.ds_ic_arrow_forward),

    /** One customer, or the user. */
    PERSON(R.drawable.ds_ic_person),

    /** The customers. */
    GROUP(R.drawable.ds_ic_group),

    /** The sale being typed. */
    CART(R.drawable.ds_ic_cart),

    /** A sale put on hold. */
    PAUSE(R.drawable.ds_ic_pause),

    /** Locked, or protected. */
    LOCK(R.drawable.ds_ic_lock),

    /** An invoice. */
    INVOICE(R.drawable.ds_ic_invoice),

    /** A setting made of several choices: the numbering. */
    TUNE(R.drawable.ds_ic_tune),

    /** The business and its tax profile. */
    BUSINESS(R.drawable.ds_ic_business),

    /** The connection to the EBMS. */
    CLOUD(R.drawable.ds_ic_cloud),

    /** The items of the catalogue. */
    ITEMS(R.drawable.ds_ic_items),

    /** The taxes. */
    TAG(R.drawable.ds_ic_tag),

    /** The counter, for the anonymous sales. */
    STORE(R.drawable.ds_ic_store),

    /** Everything was sent to the OBR. */
    CLOUD_DONE(R.drawable.ds_ic_cloud_done),

    /** No network. */
    CLOUD_OFF(R.drawable.ds_ic_cloud_off),

    /** Tries again. */
    REFRESH(R.drawable.ds_ic_refresh),
}

/** Side of the rounded square an [IconTile] draws behind its icon. */
private val TileSize = 40.dp

/**
 * An icon of the application.
 *
 * @param icon the icon.
 * @param contentDescription what the icon means, read by the screen readers; null when the text
 * next to it already says it.
 * @param modifier modifier applied to the icon.
 * @param tint colour of the icon.
 */
@Composable
fun AppIconImage(
    icon: AppIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon.drawable), contentDescription, modifier, tint)
}

/**
 * An icon on a tinted rounded square, placed at the start of a row to say what the row is about.
 * It is decorative: the text of the row carries the meaning.
 *
 * @param icon the icon.
 * @param modifier modifier applied to the tile.
 * @param container colour of the square.
 * @param content colour of the icon.
 */
@Composable
fun IconTile(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier = modifier
            .size(TileSize)
            .background(container, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        AppIconImage(icon, contentDescription = null, tint = content)
    }
}
