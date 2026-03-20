package com.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CalculatorTest {
  private val calc = Calculator()

  @Test
  fun add() {
    assertEquals(5, calc.add(2, 3))
  }

  @Test
  fun subtract() {
    assertEquals(1, calc.subtract(3, 2))
  }
}
