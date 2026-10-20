package com.dochiri.ilsim.auth.adapter.out.secret

import com.dochiri.ilsim.auth.application.port.out.TotpSecretStore
import com.dochiri.ilsim.secret.api.SecretNames
import com.dochiri.ilsim.secret.api.SecretReader
import com.dochiri.ilsim.secret.api.SecretWriter

class SecretTotpStore(private val reader: SecretReader, private val writer: SecretWriter) : TotpSecretStore {
    override fun find(username: String): String? = reader.get(SecretNames.totp(username))

    override fun save(username: String, secret: String) = writer.put(SecretNames.totp(username), secret)

    override fun delete(username: String) = writer.delete(SecretNames.totp(username))
}
