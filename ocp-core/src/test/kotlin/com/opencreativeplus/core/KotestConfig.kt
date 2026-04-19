package com.opencreativeplus.core

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.property.PropertyTesting

object KotestConfig : AbstractProjectConfig() {
    override fun beforeAll() {
        PropertyTesting.defaultIterationCount = 100
    }
}
