package com.example.app_clisa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelProvider
import com.example.app_clisa.ui.auth.AuthViewModel
import com.example.app_clisa.ui.auth.AuthScreen
import com.example.app_clisa.ui.home.HomeScreen
import com.example.app_clisa.ui.registration.RegisterScreen
import com.example.app_clisa.ui.records.RecordsScreen
import com.example.app_clisa.ui.products.ProductsScreen
import com.example.app_clisa.ui.users.UsersScreen
import com.example.app_clisa.ui.reports.ReportsScreen
import com.example.app_clisa.ui.theme.AppclisaTheme
import com.example.app_clisa.data.AccessPolicy
import com.example.app_clisa.data.AppModule

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[AuthViewModel::class.java].refreshAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val authentication = ViewModelProvider(this)[AuthViewModel::class.java]
        setContent {
            AppclisaTheme {
                var demo by rememberSaveable { mutableStateOf(false) }
                var registering by rememberSaveable { mutableStateOf(false) }
                var records by rememberSaveable { mutableStateOf(false) }
                var products by rememberSaveable { mutableStateOf(false) }
                var users by rememberSaveable { mutableStateOf(false) }
                var reports by rememberSaveable { mutableStateOf(false) }
                val authState = authentication.state
                val role = authState.profile?.role ?: if (demo) "USER" else null
                fun allowed(module: AppModule) = AccessPolicy.allows(role, module)
                fun open(module: AppModule, action: () -> Unit) {
                    if (demo) { if (allowed(module)) action() }
                    else authentication.authorize(module, action)
                }
                LaunchedEffect(authState.profile?.uid, role, demo) {
                    if (!allowed(AppModule.PRODUCTS)) products = false
                    if (!allowed(AppModule.REPORTS)) reports = false
                    if (!allowed(AppModule.USERS)) users = false
                    if (!allowed(AppModule.REGISTER)) registering = false
                    if (!allowed(AppModule.RECORDS)) records = false
                }
                val showPanel = demo || authState.profile != null
                if (showPanel && reports && allowed(AppModule.REPORTS)) {
                    ReportsScreen(onBack = { reports = false }, authenticated = !demo && authState.profile != null)
                } else if (showPanel && users && allowed(AppModule.USERS)) {
                    UsersScreen(onBack = { users = false }, authenticated = !demo && authState.profile != null)
                } else if (showPanel && products && allowed(AppModule.PRODUCTS)) {
                    ProductsScreen(onBack = { products = false }, authenticated = !demo && authState.profile != null)
                } else if (showPanel && records && allowed(AppModule.RECORDS)) {
                    RecordsScreen(onBack = { records = false }, authenticated = !demo && authState.profile != null, role = role)
                } else if (showPanel && registering && allowed(AppModule.REGISTER)) {
                    RegisterScreen(onBack = { registering = false }, demo = demo)
                } else if (showPanel) {
                    HomeScreen(onSignOut = { demo = false; registering = false; records = false; products = false; users = false; reports = false; authentication.signOut() },
                        onRefresh = { onComplete -> if (demo) onComplete() else authentication.refreshAccess(onComplete) },
                        onRegister = { open(AppModule.REGISTER) { registering = true } },
                        onRecords = { open(AppModule.RECORDS) { records = true } },
                        onProducts = { open(AppModule.PRODUCTS) { products = true } },
                        onUsers = { open(AppModule.USERS) { users = true } },
                        onReports = { open(AppModule.REPORTS) { reports = true } },
                        userName = authState.profile?.name ?: "Alexis", authenticated = authState.profile != null, role = role)
                } else {
                    AuthScreen(onDemoAccess = { demo = true }, busy = authState.busy, authMessage = authState.message,
                        onClearMessage = authentication::clearMessage, onLogin = authentication::login,
                        onRegister = authentication::register, onGoogle = authentication::google, onRecover = authentication::recover)
                }
            }
        }
    }
}
