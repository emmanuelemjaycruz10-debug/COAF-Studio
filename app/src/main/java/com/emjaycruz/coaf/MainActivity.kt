package com.emjaycruz.coaf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF101014)
private val Panel = Color(0xFF202027)
private val Purple = Color(0xFF9B5CFF)
private val White = Color(0xFFF7F4FF)
private val Green = Color(0xFF55E6A5)

class MainActivity : ComponentActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)

    setContent {
        MaterialTheme {
            CoafStudioScreen()
        }
    }
}

}

@Composable
fun CoafStudioScreen() {
Surface(
modifier = Modifier.fillMaxSize(),
color = Background
) {
Column(
modifier = Modifier
.fillMaxSize()
.padding(20.dp),
horizontalAlignment = Alignment.CenterHorizontally
) {
Text(
text = "COAF STUDIO",
color = Purple,
fontSize = 28.sp,
fontWeight = FontWeight.Black
)

        Text(
            text = "KING OF ALL FLAVORS",
            color = Green,
            fontSize = 12.sp,
            letterSpacing = 3.sp
        )

        Spacer(modifier = Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Text(
                text = "MY STUDIO",
                color = White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your music. Your sound. Your flavor.",
                color = Color.LightGray,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {},
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Record")
                }

                Button(
                    onClick = {},
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Import Beat")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "YOUR AUDIO TOOLS",
            color = Green,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        listOf(
            "🎙 Vocal Recording",
            "🎚 Multitrack Mixer",
            "🎵 Natural & Hard Auto-Tune",
            "🔎 Automatic Key Detection",
            "🎼 New Tone — Vocal Pitch Editor",
            "🎛 EQ, Reverb, Delay & More"
        ).forEach { tool ->
            Text(
                text = tool,
                color = White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Built for MJ Cruz",
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

}
