package coredevices.coreapp.auth

import PlatformUiContext
import co.touchlab.kermit.Logger
import coredevices.util.auth.AppleAuthUtil
import coredevices.util.auth.LocalIdentity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import okio.ByteString.Companion.toByteString
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationAppleIDRequest
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

actual class RealAppleAuthUtil : AppleAuthUtil {
    companion object {
        private val logger = Logger.withTag("RealAppleAuthUtil")
    }

    private fun performAuthRequest(request: ASAuthorizationAppleIDRequest) =
        callbackFlow {
            val delegate = object :
                NSObject(),
                ASAuthorizationControllerDelegateProtocol,
                ASAuthorizationControllerPresentationContextProvidingProtocol {
                override fun authorizationController(
                    controller: ASAuthorizationController,
                    didCompleteWithAuthorization: ASAuthorization
                ) {
                    val appleIDCredential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
                    trySend(appleIDCredential)
                }

                override fun authorizationController(
                    controller: ASAuthorizationController,
                    didCompleteWithError: NSError
                ) {
                    close(Exception(didCompleteWithError.localizedDescription))
                }

                override fun presentationAnchorForAuthorizationController(
                    controller: ASAuthorizationController
                ): ASPresentationAnchor =
                    UIApplication.sharedApplication.keyWindow
                        ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
                        ?: UIWindow()
            }
            val authorizationController = ASAuthorizationController(listOf(request))
            authorizationController.delegate = delegate
            authorizationController.presentationContextProvider = delegate
            authorizationController.performRequests()
            awaitClose {
                authorizationController.delegate = null
                authorizationController.presentationContextProvider = null
            }
        }

    actual override suspend fun signInApple(context: PlatformUiContext): LocalIdentity? {
        val appleIDProvider = ASAuthorizationAppleIDProvider()
        val request = appleIDProvider.createRequest().apply {
            requestedScopes = listOf(ASAuthorizationScopeEmail, ASAuthorizationScopeFullName)
        }
        return try {
            val nativeCred = performAuthRequest(request).first()
                ?: throw IllegalStateException("Cancelled or failed")
            val fullName = nativeCred.fullName?.let { name ->
                listOfNotNull(name.givenName, name.familyName)
                    .joinToString(" ")
                    .ifBlank { null }
            }
            LocalIdentity(
                provider = "apple",
                subject = nativeCred.user,
                email = nativeCred.email,
                displayName = fullName,
            )
        } catch (e: Exception) {
            logger.e(e) { "Apple sign-in failed: ${e.message}" }
            null
        }
    }
}
