package coredevices.coreapp.auth

import PlatformUiContext
import coredevices.util.auth.AppleAuthUtil
import coredevices.util.auth.LocalIdentity

expect class RealAppleAuthUtil: AppleAuthUtil {
    override suspend fun signInApple(context: PlatformUiContext): LocalIdentity?
}
