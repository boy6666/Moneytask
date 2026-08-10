package com.moneytask.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 首启引导页（M4 Onboarding）：3 步说明 + 起始按钮。授权按钮在主页仍可用。 */
@Composable
internal fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val pages = listOf(
        Triple("🧾", "离线 · 无感记账", "微信、支付宝、银行卡到账后自动入账，数据只存本机，绝不联网。"),
        Triple("🔔", "授予通知使用权", "在系统「通知使用权」中允许 Moneytask，支付到账通知才能被解析自动记账。"),
        Triple("🤖", "智能归并 · 绝不重复", "同一笔消费的多渠道通知自动归并为一笔；不同消费绝不误并。"),
    )
    val (emoji, title, desc) = pages[page]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrushBrand())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).background(Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 44.sp) }
        Spacer(Modifier.height(28.dp))
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text(desc, color = Color(0xFFE0F2F1), style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))

        // 圆点指示器
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.indices.forEach { i ->
                Box(
                    Modifier
                        .width(if (i == page) 22.dp else 8.dp)
                        .height(8.dp)
                        .background(
                            if (i == page) Color.White else Color.White.copy(alpha = 0.4f),
                            CircleShape
                        )
                )
            }
        }
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = {
                if (page < pages.lastIndex) page++ else onDone()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = CircleShape,
        ) {
            Text(if (page < pages.lastIndex) "下一步" else "开始使用",
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
        Surface(color = Color.Transparent) {
            Text("随时可在「我的」→ 重新查看使用引导", color = Color(0xFF80CBC4),
                fontSize = 12.sp)
        }
    }
}

@Composable
private fun BrushBrand() = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(BrandDeep, Brand)
)
