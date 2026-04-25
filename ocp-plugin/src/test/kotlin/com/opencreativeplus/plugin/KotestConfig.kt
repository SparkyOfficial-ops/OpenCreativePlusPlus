package com.opencreativeplus.plugin

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.property.PropertyTesting

@Suppress("OVERRIDE_DEPRECATION")
object KotestConfig : AbstractProjectConfig() {
    override fun beforeAll() {
        PropertyTesting.defaultIterationCount = 20
    }
}
