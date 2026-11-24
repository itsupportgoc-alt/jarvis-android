package ai.jarvis.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryJsonTest {
    @Test
    fun parsesRecordsAndStats() {
        val state =
            MemoryJson.parse(
                """
                {
                  "ok": true,
                  "data": {
                    "records": [
                      {
                        "id": "m-1",
                        "kind": "preference",
                        "text": "Lester likes tea",
                        "source": "user",
                        "importance": 0.8,
                        "createdAt": 100,
                        "tags": ["drink"],
                        "confidence": 1.0,
                        "userApproved": true,
                        "deleted": false
                      }
                    ],
                    "stats": {
                      "total": 3,
                      "active": 1,
                      "expired": 2,
                      "corrupt": 0,
                      "byKind": {"preference": 1}
                    }
                  }
                }
                """.trimIndent(),
                query = "tea",
            )
        assertNull(state.error)
        assertEquals(1, state.entries.size)
        assertEquals("preference", state.entries[0].kind)
        assertEquals(0.8f, state.entries[0].importance, 0.001f)
        assertTrue(state.entries[0].approved)
        assertNull(state.entries[0].expiresAt)
        assertEquals(3, state.total)
        assertEquals(1, state.active)
        assertEquals(1, state.byKind["preference"])
        assertEquals("tea", state.query)
    }

    @Test
    fun surfacesTypedErrorsAndKeepsTheQuery() {
        val state =
            MemoryJson.parse(
                """
                {"ok":false,"error":{"kind":"permissionDenied","detail":"permanent memory needs explicit user approval"}}
                """.trimIndent(),
                query = "birthday",
            )
        assertEquals("permanent memory needs explicit user approval", state.error)
        assertEquals("birthday", state.query)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun toleratesMalformedAndPartialRecords() {
        val state =
            MemoryJson.parse(
                """
                {"ok":true,"data":{"records":[{"id":"missing-text"},{"text":"missing id"},{"id":"m-2","text":"kept","kind":"task"}]}}
                """.trimIndent(),
            )
        assertNull(state.error)
        assertEquals(1, state.entries.size)
        assertEquals("m-2", state.entries[0].id)
        assertEquals(0, state.total)
    }

    @Test
    fun malformedJsonBecomesAnErrorNotACrash() {
        val state = MemoryJson.parse("not json")
        assertNotNull(state.error)
        assertTrue(state.entries.isEmpty())
    }
}
