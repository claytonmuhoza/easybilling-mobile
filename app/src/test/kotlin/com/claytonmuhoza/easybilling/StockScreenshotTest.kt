package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.claytonmuhoza.easybilling.access.LocalPermissions
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.stock.MovementId
import com.claytonmuhoza.easybilling.domain.stock.MovementProblem
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockLine
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.domain.transmission.MovementToResolve
import com.claytonmuhoza.easybilling.domain.transmission.OutboxEntry
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.QueuedDocument
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionStatus
import com.claytonmuhoza.easybilling.identity.ApprovalUiState
import com.claytonmuhoza.easybilling.identity.Approver
import com.claytonmuhoza.easybilling.stock.MovementRow
import com.claytonmuhoza.easybilling.stock.StockItemActions
import com.claytonmuhoza.easybilling.stock.StockItemScreen
import com.claytonmuhoza.easybilling.stock.StockItemUiState
import com.claytonmuhoza.easybilling.stock.StockListActions
import com.claytonmuhoza.easybilling.stock.StockListScreen
import com.claytonmuhoza.easybilling.stock.StockListUiState
import com.claytonmuhoza.easybilling.stock.StockMovementActions
import com.claytonmuhoza.easybilling.stock.StockMovementScreen
import com.claytonmuhoza.easybilling.stock.StockMovementUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.testing.TransmissionSamples
import com.claytonmuhoza.easybilling.transmission.TransmissionActions
import com.claytonmuhoza.easybilling.transmission.TransmissionBanner
import com.claytonmuhoza.easybilling.transmission.TransmissionScreen
import com.claytonmuhoza.easybilling.transmission.TransmissionUiState
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test

/**
 * Screenshots of the stock (UC-F01, F02, F05, F07, F10): its list, the stock of an item, the entry
 * of a movement and what the transmission says of the movements, in every [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class StockScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val now = TransmissionSamples.NOW

    private fun item(id: Long, designation: String, unit: String = "pièce") = Item(
        id = ItemId(id),
        code = "ART-%04d".format(id),
        details = ItemDetails(
            designation = designation,
            unit = unit,
            price = Money.of("1500", Currency.BIF),
            priceMode = PriceMode.INCLUDING_TAXES,
            taxes = emptyMap(),
            stockTracked = true,
        ),
    )

    private val soap = item(1, "Savon de Marseille")

    private val lines = listOf(
        StockLine(soap, BigDecimal("148")),
        StockLine(item(2, "Huile de palme raffinée, bidon de 5 litres", unit = "bidon"), BigDecimal("12.5")),
        StockLine(item(3, "Sucre blanc", unit = "kg"), BigDecimal.ZERO),
    )

    private fun movement(
        id: Long,
        type: MovementType,
        quantity: String,
        description: String = "",
        age: Duration = Duration.ofHours(id),
    ) = StockMovement(
        id = MovementId(id),
        content = StockMovementContent(
            reference = "A1-%06d".format(id),
            itemId = soap.id,
            itemCode = soap.code,
            designation = soap.details.designation,
            unit = soap.details.unit,
            type = type,
            quantity = Quantity.of(quantity),
            unitCost = Money.of("812.5", Currency.BIF),
            description = description,
            occurredAt = now - age,
            environment = EbmsEnvironment.TEST,
            recordedBy = UserId(1),
        ),
    )

    private val loss = movement(4, MovementType.SP, "2", "Carton mouillé pendant le déchargement")
    private val purchase = movement(3, MovementType.EN, "50", "Facture fournisseur F-88")
    private val refusal = MovementStatus.Rejected("Identifiant système incorrect.")

    private val history = listOf(
        MovementRow(loss, MovementStatus.Pending),
        MovementRow(purchase, MovementStatus.ToConfirm),
        MovementRow(movement(2, MovementType.ST, "20", "Vers le dépôt de Gitega"), refusal),
        MovementRow(movement(1, MovementType.EI, "120"), MovementStatus.Declared),
    )

    private fun form(exit: Boolean) = StockMovementUiState.Form(soap, BigDecimal("148"), exit = exit)

    @Composable
    private fun list(state: StockListUiState) = StockListScreen(state, StockListActions())

    @Composable
    private fun detail(state: StockItemUiState) = StockItemScreen(state, StockItemActions())

    @Composable
    private fun entry(state: StockMovementUiState) = StockMovementScreen(state, StockMovementActions())

    private fun transmission(queue: List<QueuedDocument> = emptyList()) = TransmissionUiState.Ready(
        TransmissionStatus(
            queue = queue,
            sentToday = 12,
            stockToResolve = listOf(
                MovementToResolve(purchase, MovementStatus.ToConfirm),
                MovementToResolve(loss, refusal),
            ),
            readAt = now,
        ),
    )

    @Test
    fun stockList() = capture("stock_list") {
        list(StockListUiState.Ready(query = "", lines = lines))
    }

    @Test
    fun stockListNoMatch() = capture("stock_list_no_match") {
        list(StockListUiState.Ready(query = "farine", lines = emptyList()))
    }

    @Test
    fun stockListEmpty() = capture("stock_list_empty") {
        list(StockListUiState.Ready(query = "", lines = emptyList()))
    }

    @Test
    fun stockListLoading() = capture("stock_list_loading", animated = true) {
        list(StockListUiState.Loading)
    }

    @Test
    fun stockListError() = capture("stock_list_error") {
        list(StockListUiState.Failed)
    }

    @Test
    fun stockItem() = capture("stock_item") {
        detail(StockItemUiState.Ready(soap, BigDecimal("148"), history))
    }

    @Test
    fun stockItemWithoutMovement() = capture("stock_item_without_movement") {
        detail(StockItemUiState.Ready(soap, BigDecimal.ZERO, emptyList()))
    }

    @Test
    fun stockItemReadOnly() = capture("stock_item_read_only") {
        CompositionLocalProvider(LocalPermissions provides setOf(Permission.VIEW_STOCK)) {
            detail(StockItemUiState.Ready(soap, BigDecimal("148"), history.takeLast(1)))
        }
    }

    @Test
    fun stockItemArchived() = capture("stock_item_archived") {
        detail(StockItemUiState.Ready(soap.copy(archived = true), BigDecimal("148"), history.takeLast(1)))
    }

    @Test
    fun stockItemUnavailable() = capture("stock_item_unavailable") {
        detail(StockItemUiState.Unavailable(StockProblem.ITEM_NOT_TRACKED))
    }

    @Test
    fun stockItemLoading() = capture("stock_item_loading", animated = true) {
        detail(StockItemUiState.Loading)
    }

    @Test
    fun stockItemError() = capture("stock_item_error") {
        detail(StockItemUiState.Failed)
    }

    @Test
    fun stockEntry() = capture("stock_entry") {
        entry(
            form(exit = false).copy(
                type = MovementType.EN,
                quantity = "50",
                unitCost = "812,5",
                description = "Facture fournisseur F-88",
            ),
        )
    }

    @Test
    fun stockEntryProblems() = capture("stock_entry_problems") {
        entry(
            form(exit = false).copy(
                quantity = "0",
                problems = setOf(
                    MovementProblem.TYPE_MISSING,
                    MovementProblem.QUANTITY_INVALID,
                    MovementProblem.COST_INVALID,
                ),
            ),
        )
    }

    @Test
    fun stockExit() = capture("stock_exit") {
        entry(
            form(exit = true).copy(
                needsApproval = MovementType.VALIDATED_BY_MANAGER,
                type = MovementType.SP,
                quantity = "2",
                description = "Carton mouillé pendant le déchargement",
            ),
        )
    }

    @Test
    fun stockExitInsufficient() = capture("stock_exit_insufficient") {
        entry(
            form(exit = true).copy(
                type = MovementType.ST,
                quantity = "200",
                problems = setOf(MovementProblem.DESCRIPTION_REQUIRED),
                insufficient = BigDecimal("148"),
                failed = true,
            ),
        )
    }

    @Test
    fun stockExitApproval() = capture("stock_exit_approval") {
        entry(
            form(exit = true).copy(
                needsApproval = MovementType.VALIDATED_BY_MANAGER,
                type = MovementType.SV,
                quantity = "3",
                description = "Constat du 3 octobre",
                approval = ApprovalUiState(listOf(Approver(2, "Marc Ndayizeye"), Approver(3, "Alice Irakoze"))),
            ),
        )
    }

    @Test
    fun stockMovementUnavailable() = capture("stock_movement_unavailable") {
        entry(StockMovementUiState.Unavailable(StockProblem.ITEM_ARCHIVED))
    }

    @Test
    fun stockMovementLoading() = capture("stock_movement_loading", animated = true) {
        entry(StockMovementUiState.Loading)
    }

    @Test
    fun stockMovementError() = capture("stock_movement_error") {
        entry(StockMovementUiState.Failed)
    }

    @Test
    fun transmissionStockBanner() = capture("transmission_banner_stock") {
        TransmissionBanner(transmission(), compact = false, onOpen = {}, modifier = Modifier.padding(Spacing.large))
    }

    @Test
    fun transmissionStock() = capture("transmission_stock") {
        val entry = OutboxEntry(
            id = 7,
            operation = OutboxOperation.SendStockMovement(loss.id),
            createdAt = now - Duration.ofMinutes(4),
            attempts = 1,
            nextAttemptAt = now + Duration.ofMinutes(1),
            uncertain = false,
            lastError = "Failed to connect to ebms.obr.gov.bi",
        )
        val queue = listOf(QueuedDocument(entry, invoice = null, movement = loss))
        TransmissionScreen(transmission(queue), TransmissionActions())
    }

    @Test
    fun transmissionStockReadOnly() = capture("transmission_stock_read_only") {
        CompositionLocalProvider(LocalPermissions provides setOf(Permission.VIEW_TRANSMISSION)) {
            TransmissionScreen(transmission(), TransmissionActions())
        }
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
