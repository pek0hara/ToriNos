package com.nostr.torinos.ui.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EditProfileSaveTest {
    @Test
    fun cannotSaveBeforeExistingProfileIsLoaded() {
        // 読み込み前の空欄のまま保存すると、今のプロフィールが空で上書きされる。
        val blocker = profileSaveBlocker(EditProfileState(loadState = ProfileLoadState.Loading))
        assertNotNull(blocker)
    }

    @Test
    fun canSaveAfterLoadOrWhenNoProfileWasFound() {
        assertNull(profileSaveBlocker(EditProfileState(name = "alice", loadState = ProfileLoadState.Loaded)))
        // 初めてのアカウントなど、プロフィールが見つからなかったときは入力した内容で作る。
        assertNull(profileSaveBlocker(EditProfileState(loadState = ProfileLoadState.NotFound)))
    }

    @Test
    fun lightningAddressMustBeNameAtDomain() {
        assertEquals(
            "Lightning アドレスは name@example.com の形で入力してください",
            profileSaveBlocker(EditProfileState(lud16 = "alice", loadState = ProfileLoadState.Loaded)),
        )
        assertNull(profileSaveBlocker(EditProfileState(lud16 = " alice@example.com ", loadState = ProfileLoadState.Loaded)))
        assertNull(profileSaveBlocker(EditProfileState(lud16 = "", loadState = ProfileLoadState.Loaded)))
    }

    @Test
    fun newProfileStartsLoadedWhenNotLoadingExisting() {
        assertEquals(ProfileLoadState.NotFound, EditProfileState.initial(loadExistingProfile = false).loadState)
        assertEquals(ProfileLoadState.Loading, EditProfileState.initial(loadExistingProfile = true).loadState)
    }

    @Test
    fun profileDetailEditChangesOnlyThatField() {
        // 設定画面の「プロフィールの詳細」は1項目だけ変える。ほかは読み込んだ最新の内容のまま保存する。
        val loaded = EditProfileState(
            name = "alice",
            displayName = "Alice",
            about = "hello",
            picture = "https://example.com/a.png",
            banner = "https://example.com/b.png",
            nip05 = "alice@example.com",
            lud16 = "alice@wallet.example",
            lud06 = "lnurl1xyz",
            loadState = ProfileLoadState.Loaded,
        )
        ProfileDetailField.entries.forEach { field ->
            val edited = loaded.withProfileDetail(field, "changed")
            assertEquals("changed", edited.profileDetail(field))
            ProfileDetailField.entries.filter { it != field }.forEach { other ->
                assertEquals(loaded.profileDetail(other), edited.profileDetail(other), "$field を変えたら $other も変わった")
            }
            assertEquals(loaded.copy(), edited.withProfileDetail(field, loaded.profileDetail(field)))
        }
    }

    @Test
    fun profileDetailReadsEachField() {
        val state = EditProfileState(picture = "p", banner = "b", nip05 = "n", lud16 = "l")
        assertEquals("p", state.profileDetail(ProfileDetailField.Picture))
        assertEquals("b", state.profileDetail(ProfileDetailField.Banner))
        assertEquals("n", state.profileDetail(ProfileDetailField.Nip05))
        assertEquals("l", state.profileDetail(ProfileDetailField.Lud16))
    }

    @Test
    fun canSaveWhileRefreshingCachedProfile() {
        // キャッシュを出して取り直し中でも保存は押せる（保存は取り直しの完了を待ってから送る）。
        assertNull(profileSaveBlocker(EditProfileState(name = "alice", loadState = ProfileLoadState.Refreshing)))
    }

    @Test
    fun freshProfileReplacesOnlyUntouchedFields() {
        val cached = ProfileFields(
            name = "alice",
            displayName = "Alice",
            about = "old about",
            picture = "https://example.com/a.png",
            banner = "",
            nip05 = "alice@example.com",
            lud16 = "old@wallet.example",
            lud06 = "",
        )
        val fresh = cached.copy(
            displayName = "Alice (new)",
            about = "new about from other client",
            lud16 = "new@wallet.example",
            lud06 = "lnurl1new",
        )
        // キャッシュで開いたあと、自己紹介だけ書き換えた。
        val editing = EditProfileState(loadState = ProfileLoadState.Refreshing).withFields(cached)
            .copy(about = "typed by me")

        val merged = editing.mergeFresh(base = cached, fresh = fresh)

        assertEquals("typed by me", merged.about, "触った欄は入力を残す")
        assertEquals("Alice (new)", merged.displayName, "触っていない欄は最新にする")
        assertEquals("new@wallet.example", merged.lud16, "他のクライアントで変えた値を古い値で上書きしない")
        assertEquals("lnurl1new", merged.lud06, "編集欄のない項目は常に最新")
        assertEquals("alice", merged.name)
        assertEquals(ProfileLoadState.Loaded, merged.loadState)
    }

    @Test
    fun freshProfileKeepsFieldUserChangedEvenIfRemoteAlsoChanged() {
        val cached = ProfileFields(name = "alice")
        val fresh = cached.copy(name = "remote")
        val merged = EditProfileState(loadState = ProfileLoadState.Refreshing).withFields(cached)
            .copy(name = "mine")
            .mergeFresh(base = cached, fresh = fresh)
        assertEquals("mine", merged.name)
    }

    @Test
    fun freshProfileKeepsUploadedImage() {
        val cached = ProfileFields(picture = "https://example.com/old.png")
        val merged = EditProfileState(loadState = ProfileLoadState.Refreshing).withFields(cached)
            .copy(picture = "https://example.com/uploaded.png")
            .mergeFresh(base = cached, fresh = cached.copy(picture = "https://example.com/remote.png"))
        assertEquals("https://example.com/uploaded.png", merged.picture)
    }
}
