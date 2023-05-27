package com.diggsey.dpass

object RequestCode {
    private var counter = 100
    val SELECT_FILE = counter++
    val DOWNLOAD_FILE = counter++
}