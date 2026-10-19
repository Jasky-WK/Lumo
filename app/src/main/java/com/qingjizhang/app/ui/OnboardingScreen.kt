package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.ui.theme.*

/**
 * v2.9.0：首启三屏引导（只对全新安装出现一次）。
 *
 * 1. 隐私：没有网络权限，数据只在手机里；
 * 2. 记一笔：打开即记、记完即走；
 * 3. 数据安全：备份随时带走，换机能复原。
 *
 * 最后一屏给两个出口：「开始记账」与「先看看示例账本」（生成一个独立示例账本）。
 * 老用户不会看到：onboarded 标志在升级后的首次启动会被视为已完成（见 AppViewModel.onboarded）。
 */
private data class OnboardPage(val icon: ImageVector, val title: String, val body: String)

private val PAGES = listOf(
    OnboardPage(
        Icons.Rounded.Lock,
        "你的账，只存在你的手机",
        "Lumo 没有网络权限——不是「承诺不联网」，是安装包里根本没有这一项。\n账目、账户、备份全部留在本机，没有账号，没有云同步。"
    ),
    OnboardPage(
        Icons.Rounded.Edit,
        "打开即记，记完即走",
        "三 Tab 就够用：首页看流水、账户看余额、总览看结构。\n记一笔自带计算器，支持「再记」连续记账，一句话也能记（设置里可开）。"
    ),
    OnboardPage(
        Icons.Rounded.Archive,
        "数据随时带走，也随时找得回",
        "完整备份是一个文件，存哪由你决定；每月还会自动备份一次。\n换手机、重装，导入即可复原——账户配置、借贷关联、回收站都在。"
    )
)

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onTryDemo: () -> Unit
) {
    var page by remember { mutableStateOf(0) }
    val p = PAGES[page]

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 28.dp)
    ) {
        Spacer(Modifier.height(96.dp))
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(PrimaryBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(p.icon, contentDescription = null, tint = Primary, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(34.dp))
        Text(p.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 34.sp)
        Spacer(Modifier.height(14.dp))
        Text(p.body, fontSize = 14.sp, color = Sub2, lineHeight = 23.sp)
        Spacer(Modifier.weight(1f))

        // 页码圆点
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            PAGES.indices.forEach { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = if (i == page) 18.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(if (i == page) Primary else Track)
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        if (page < PAGES.lastIndex) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                Text(
                    "下一步",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { page++ }
                        .padding(vertical = 14.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                "跳过",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDone() }
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                color = Sub
            )
        } else {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
            ) {
                Text(
                    "开始记账",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDone() }
                        .padding(vertical = 14.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "先看看示例账本",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTryDemo() }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primary
            )
        }
        Spacer(Modifier.height(36.dp))
    }
}
