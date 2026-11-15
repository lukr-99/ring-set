package com.krejci.halo.ui

/**
 * Who made the app and where to reach them, shown on the About screen. Plain constants: the links
 * open in the system browser, so nothing here is sent anywhere by the app itself.
 */
object AuthorLinks {
    const val NAME: String = "Lukáš Krejčí"

    const val INSTAGRAM_HANDLE: String = "lukas9krejci"
    const val INSTAGRAM_URL: String = "https://www.instagram.com/$INSTAGRAM_HANDLE/"

    const val GITHUB_HANDLE: String = "lukr-99"
    const val GITHUB_URL: String = "https://github.com/$GITHUB_HANDLE"

    const val REPO_NAME: String = "ring-set"
    const val ISSUES_URL: String = "https://github.com/$GITHUB_HANDLE/$REPO_NAME/issues"
}
