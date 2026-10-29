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
}
