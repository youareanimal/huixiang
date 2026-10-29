package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.R

@Composable
fun EntryScreen(onLogin: () -> Unit, onGuest: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ts.header, Ts.page)))
        .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.app_name), color = Ts.bright, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text("VOICE · FRIENDS · YOU", color = Ts.brand, fontSize = 10.sp, letterSpacing = 1.sp)
        }
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Ts.accent.copy(alpha = .17f), radius = size.minDimension * .43f)
                drawCircle(Ts.talking.copy(alpha = .20f), radius = 14.dp.toPx(), center = center.copy(x = center.x * .35f, y = center.y * 1.5f))
                drawCircle(Ts.brand.copy(alpha = .7f), radius = 5.dp.toPx(), center = center.copy(x = center.x * 1.7f, y = center.y * .6f))
            }
            Image(painterResource(R.drawable.huixiang_mascot), null, Modifier.size(182.dp).rotate(-8f))
        }
        Text("让声音，\n找到同频的人。", color = Ts.bright, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp)
        Spacer(Modifier.height(13.dp))
        Text("熟悉的频道，聊得来的好友。\n在回响，随时一起开聊。", color = Ts.muted, fontSize = 15.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(30.dp))
        PrimaryButton("登录 TeamSpeak 账号", modifier = Modifier.fillMaxWidth(), onClick = onLogin)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onGuest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
            Text("先以访客进入", color = Ts.text, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("登录同步书签和好友 · 访客也能连接语音服务器", color = Ts.muted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
    }
}
