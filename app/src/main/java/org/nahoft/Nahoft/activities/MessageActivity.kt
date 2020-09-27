package org.nahoft.Nahoft.activities

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_message.*
import kotlinx.android.synthetic.main.message_item_row.*
import org.nahoft.codex.Encryption
import org.nahoft.Nahoft.Message
import org.nahoft.Nahoft.R

class MessageActivity : AppCompatActivity() {

    class Arguments(val message: Message) {
        companion object {
            const val messageKey = "Message"

            fun createFromIntent(intent: Intent): Arguments {
                return Arguments(message = intent.getSerializableExtra(messageKey) as Message)
            }
        }

        fun startActivity(context: Context) {
            val intent = Intent(context, MessageActivity::class.java)
            intent.putExtra(messageKey, message)
            context.startActivity(intent)
        }
    }

    lateinit var message: Message

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message)

        val arguments = Arguments.createFromIntent(intent)
        message = arguments.message


        loadMessageContent()
    }

    fun loadMessageContent() {

        sender_name_text_view.text = message.sender?.name

        val senderKeyBytes = message.sender?.publicKeyEncoded?.let { it }

        if (senderKeyBytes != null) {
            val senderKey = Encryption.publicKeyFromByteArray(senderKeyBytes)?.let { it }

            if (senderKey != null) {
                val plaintext = Encryption(this).decrypt(senderKey, message.cipherText)?.let { it }

                if (plaintext != null) {
                    message_body_text_view.text = plaintext
                }
            }

        } else {
            print("Failed to get sender public key for a message")
            return
        }

    }
}