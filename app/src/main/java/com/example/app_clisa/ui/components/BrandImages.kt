package com.example.app_clisa.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.app_clisa.R

@Composable
fun ClisaLogo(modifier: Modifier = Modifier, width: Dp = 215.dp) {
    Image(
        painter = painterResource(R.drawable.clisa_logo),
        contentDescription = "CLISA Technology",
        contentScale = ContentScale.Fit,
        modifier = modifier.width(width),
    )
}
