/**
 * @file
 * @brief Application entry point that bootstraps the Hilt dependency-injection graph.
 */
package com.kempt.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * @brief Custom @c Application subclass that initialises Hilt for the whole app.
 *
 * @details The @c @HiltAndroidApp annotation triggers Hilt's code generation and creates
 * the application-wide dependency container that every other injected component (the
 * activity, services, workers) draws from. This class is named in @c AndroidManifest.xml via
 * @c android:name so the Android runtime instantiates it before any other component.
 *
 * @note The class has no body: in Kotlin @c ": Application()" both names the superclass and
 * invokes its no-argument constructor, so no braces are needed.
 */
@HiltAndroidApp
class KemptApplication : Application()
