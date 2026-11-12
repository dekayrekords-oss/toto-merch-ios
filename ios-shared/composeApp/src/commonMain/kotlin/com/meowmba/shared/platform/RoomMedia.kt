package com.meowmba.shared.platform

/**
 * Android RoomMediaWatcher on iOS. iOS shares only Apple Music's now-playing with other apps
 * (with Media & Apple Music permission); YouTube and audiobook apps stay private to themselves.
 */
interface MusicDriver {
    /** "title|artist|playing" for Apple Music, or null when nothing is loaded / no permission. */
    fun nowPlaying(): String?
    fun hasAccess(): Boolean
    fun requestMusicAccess(onResult: (Boolean) -> Unit)
    fun togglePlay()
    fun next()
}
