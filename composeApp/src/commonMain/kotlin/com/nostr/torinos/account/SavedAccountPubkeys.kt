package com.nostr.torinos.account

import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * 端末に保存済みのアカウントのpubkey。アカウントの状態が変わるたびに取り直し、各キャッシュで共有する。
 * キャッシュがこれらのアカウントのデータを追い出さず、優先して保存するために使う。
 * 一覧を読めなかったときは、直前の値を保つため何も流さない。
 */
internal val SavedAccountPubkeys: Flow<Set<String>> = AccountSessions.manager.state
    .map {
        try {
            AccountSessions.manager.listAccounts().mapTo(hashSetOf()) { it.pubkeyHex }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException("SavedAccountPubkeys", e, "Failed to load accounts")
            null
        }
    }
    .filterNotNull()
    .distinctUntilChanged()
    .shareIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Lazily, replay = 1)
