package com.example.finance_planning.ui.current

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource as text
import com.example.finance_planning.R
import com.example.finance_planning.core.OrderContent
import com.example.finance_planning.network.TradeDraft
import java.math.BigDecimal

/** Labels only. The caller still owns review, verification and submission guards. */
@Composable
internal fun TradeReviewContent(account: String, draft: TradeDraft, tradingPackage: String) {
    SectionCard(text(R.string.trade_review_button)) {
        DetailGrid(listOf(
            text(R.string.account_2) to account,
            text(R.string.current_ui_symbol) to draft.symbol,
            text(R.string.current_ui_side) to text(if (draft.side == "NB") R.string.buy else R.string.sell),
            text(R.string.trade_quantity) to text(R.string.current_ui_shares, draft.quantity),
            text(R.string.trade_price_vnd) to OrderContent.money(BigDecimal(draft.price)),
            text(R.string.current_ui_total) to OrderContent.money(BigDecimal(draft.price).multiply(BigDecimal(draft.quantity))),
            text(R.string.trade_package) to tradingPackage))
        Text(text(R.string.current_ui_fees_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
