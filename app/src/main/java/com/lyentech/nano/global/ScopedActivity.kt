package com.lyentech.nano.global

import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

abstract class ScopedActivity : ComponentActivity (){

    protected  val scope = CoroutineScope (SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
    }
}