package com.example.bookstock.modules.stock

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.AccountPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * トークン取得結果
 */
sealed class TokenResult {
    data class Success(val token: String) : TokenResult()
    data class NeedConsent(val intent: Intent) : TokenResult()
    data class Error(val message: String) : TokenResult()
}

/**
 * Googleアカウント認証およびOAuthアクセストークン管理ヘルパー
 * Google Sheets API v4 閲覧権限（spreadsheets.readonly）のトークンを取得します。
 */
class GoogleAuthHelper(private val context: Context) {

    private val prefs = context.getSharedPreferences("google_auth_prefs", Context.MODE_PRIVATE)
    private val PREF_ACCOUNT_NAME = "account_name"

    // Google Sheets API 読み取り専用スコープ
    private val SCOPE = "oauth2:https://www.googleapis.com/auth/spreadsheets.readonly"

    /**
     * 保存されているアカウント名を取得（未サインインの場合はnull）
     */
    fun getSavedAccountName(): String? {
        return prefs.getString(PREF_ACCOUNT_NAME, null)
    }

    /**
     * アカウント名を保存
     */
    fun saveAccountName(accountName: String) {
        prefs.edit().putString(PREF_ACCOUNT_NAME, accountName).apply()
    }

    /**
     * サインアウト（保存済みアカウント情報を破棄）
     */
    fun signOut() {
        prefs.edit().remove(PREF_ACCOUNT_NAME).apply()
    }

    /**
     * Googleアカウント選択ダイアログを表示するためのIntentを作成
     */
    fun newChooseAccountIntent(): Intent {
        val currentAccount = getSavedAccountName()?.let { Account(it, "com.google") }
        return AccountPicker.newChooseAccountIntent(
            AccountPicker.AccountChooserOptions.Builder()
                .setSelectedAccount(currentAccount)
                .setAllowableAccountsTypes(listOf("com.google"))
                .setAlwaysShowAccountPicker(false)
                .build()
        )
    }

    /**
     * 指定されたGoogleアカウントのOAuthアクセストークンを取得
     * 初回権限許可が必要な場合は NeedConsent(intent) を返します
     */
    suspend fun fetchToken(accountName: String? = getSavedAccountName()): TokenResult = withContext(Dispatchers.IO) {
        if (accountName.isNullOrEmpty()) {
            return@withContext TokenResult.Error("アカウント名が未指定です")
        }
        try {
            val account = Account(accountName, "com.google")
            val token = GoogleAuthUtil.getToken(context, account, SCOPE)
            Log.d("GoogleAuthHelper", "OAuthトークン取得成功: $accountName")
            TokenResult.Success(token)
        } catch (e: UserRecoverableAuthException) {
            Log.w("GoogleAuthHelper", "ユーザー同意画面が必要です(UserRecoverableAuthException): ${e.message}")
            val consentIntent = e.intent
            if (consentIntent != null) {
                TokenResult.NeedConsent(consentIntent)
            } else {
                TokenResult.Error("同意インテントの取得に失敗しました: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("GoogleAuthHelper", "OAuthトークン取得エラー: ${e.message}", e)
            TokenResult.Error(e.message ?: "認証エラー")
        }
    }
}
