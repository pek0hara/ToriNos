package com.nostr.torinos

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
internal expect fun BindBrowserNavigation(navController: NavHostController)
