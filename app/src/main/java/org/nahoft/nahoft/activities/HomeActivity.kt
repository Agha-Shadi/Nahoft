package org.nahoft.nahoft.activities

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_home.*
import kotlinx.android.synthetic.main.activity_create.*
import kotlinx.android.synthetic.main.activity_read.*
import kotlinx.coroutines.*
import org.nahoft.codex.Codex
import org.nahoft.codex.KeyOrMessage
import org.nahoft.codex.LOGOUT_TIMER_VAL
import org.nahoft.codex.LogoutTimerBroadcastReceiver
import org.nahoft.nahoft.*
import org.nahoft.nahoft.Persist.Companion.friendsFilename
import org.nahoft.nahoft.Persist.Companion.messagesFilename
import org.nahoft.nahoft.Persist.Companion.status
import org.nahoft.org.nahoft.swatch.Decoder
import org.nahoft.util.RequestCodes
import org.nahoft.util.showAlert
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HomeActivity : AppCompatActivity() {

    private val parentJob = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + parentJob)

    private val receiver by lazy {
        LogoutTimerBroadcastReceiver {
            cleanUp()
        }
    }

    private var decodePayload: ByteArray? = null

    @ExperimentalUnsignedTypes
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        registerReceiver(receiver, IntentFilter().apply {
            addAction(LOGOUT_TIMER_VAL)
        })

        Persist.app = Nahoft()

        // Load status from preferences
        Persist.loadEncryptedSharedPreferences(this.applicationContext)
        getStatus()

        // Check LoginStatus
        if (status == LoginStatus.NotRequired || status == LoginStatus.LoggedIn)
        {
            // We may not have initialized shared preferences yet, let's do it now
            Persist.loadEncryptedSharedPreferences(this.applicationContext)

            // Receive shared messages
            if (intent?.action == Intent.ACTION_SEND)
            {
                if (intent.type == "text/plain")
                {
                    handleSharedText(intent)
                }
                else if (intent.type?.startsWith("image/") == true)
                {
                    handleSharedImage(intent)
                }
                else
                {
                    showAlert(getString(R.string.alert_text_unable_to_process_request))
                }
            }
            else // See if we got intent extras from the EnterPasscode Activity
            {
                // Check to see if we received a send intent
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let{
                    // Received string message
                    decodeStringMessage(it)
                }

                // See if we received an image message
                val extraStream = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM)
                if (extraStream != null){
                    val extraUri = Uri.parse(extraStream.toString())
                    decodeImage(extraUri)
                }
                else
                {
                    println("Extra Stream is Null")
                }
            }
        }
        else
        {
            sendToLogin()
        }

        // Logout Button
        if (status == LoginStatus.NotRequired) {
            logout_button.visibility = View.INVISIBLE
        } else {
            logout_button.visibility = View.VISIBLE
        }

        logout_button.setOnClickListener {
            logoutButtonClicked()
        }

        // Read
        read_button.setOnClickListener {
            val messagesIntent = Intent(this, ReadActivity::class.java)
            startActivity(messagesIntent)
        }

        create_button.setOnClickListener {
            val createIntent = Intent(this, CreateActivity::class.java)
            startActivity(createIntent)
        }
        
        // User Guide
        user_guide_button.setOnClickListener {
            val userGuideIntent = Intent(this, HelpActivity::class.java)
            startActivity(userGuideIntent)
        }

        // Friends
        friends_button.setOnClickListener {
            val friendsIntent = Intent(this, FriendsListActivity::class.java)
            startActivity(friendsIntent)
        }

        // Settings
        settings_button.setOnClickListener {
            val settingsIntent = Intent(this, SettingPasscodeActivity::class.java)
            startActivity(settingsIntent)
        }

        // Load friends from file
        getStatus()
        setupFriends()
        loadSavedMessages()
    }

    override fun onResume() {
        super.onResume()

        if (status == LoginStatus.NotRequired) {
            logout_button.visibility = View.INVISIBLE
        } else {
            logout_button.visibility = View.VISIBLE
        }

    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun sendToLogin()
    {
        // If the status is not either NotRequired, or Logged in, request login
        this.showAlert(getString(R.string.alert_text_passcode_required_to_proceed))

        // Send user to the EnterPasscode Activity
        val loginIntent = Intent(applicationContext, EnterPasscodeActivity::class.java)

        // We received a shared message but the user is not logged in
        // Save the intent
        if (intent?.action == Intent.ACTION_SEND)
        {
            if (intent.type == "text/plain")
            {
                val messageString = intent.getStringExtra(Intent.EXTRA_TEXT)
                loginIntent.putExtra(Intent.EXTRA_TEXT, messageString)
            }
            else if (intent.type?.startsWith("image/") == true)
            {
                val extraStream = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM)
                if (extraStream != null){
                    val extraUri = Uri.parse(extraStream.toString())
                    loginIntent.putExtra(Intent.EXTRA_STREAM, extraUri)
                }
                else
                {
                    println("Extra Stream is Null")
                }
            }
            else
            {
                showAlert(getString(R.string.alert_text_unable_to_process_request))
            }
        }

        startActivity(loginIntent)
    }

    private fun showDialogButtonHomeHelp() {
        AlertDialog.Builder(this)
            .setTitle(resources.getString(R.string.dialog_button_home_help_title))
            .setMessage(resources.getString(R.string.dialog_button_home_help))
            .setPositiveButton(resources.getString(R.string.ok_button)) {
                    dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == RequestCodes.selectMessageSenderCode) {
                val sender = data?.getSerializableExtra(RequestCodes.friendExtraTaskDescription)?.let { it as Friend }

                if (sender != null && decodePayload != null) {
                    // Create Message Instance
                    val date = LocalDateTime.now()
                    val stringDate = date.format(DateTimeFormatter.ofPattern("M/d/y H:m"))
                    val cipherText = decodePayload

                    val newMessage = Message(stringDate, cipherText!!, sender)
                    Persist.messageList.add(newMessage)
                    Persist.saveMessagesToFile(this)

                    // Go to message view
                    val messageArguments = MessageActivity.Arguments(message = newMessage)
                    messageArguments.startActivity(this)
                }

            } else if (requestCode == RequestCodes.selectKeySenderCode)
            {
                val sender = data?.getSerializableExtra(RequestCodes.friendExtraTaskDescription)?.let { it as Friend }

                // Update this friend with a new key and a new status
                if (sender != null && decodePayload != null)
                {
                    when (sender.status) {
                        FriendStatus.Default -> {
                            Persist.updateFriend(context = this, friendToUpdate = sender, newStatus = FriendStatus.Requested, encodedPublicKey = decodePayload!!)
                            this.showAlert(getString(R.string.alert_text_received_invitation, sender.name))
                        }

                        FriendStatus.Invited -> {
                            Persist.updateFriend(context = this, friendToUpdate = sender, newStatus = FriendStatus.Approved, encodedPublicKey = decodePayload!!)
                            this.showAlert(sender.name,(R.string.alert_text_invitation_accepted))
                        }

                        else ->
                            this.showAlert(getString(R.string.alert_text_unable_to_update_friend_status))
                    }
                } else
                {
                    this.showAlert(getString(R.string.alert_text_unable_to_update_friend_status))
                }
            }
        }
    }

    private fun handleSharedText(intent: Intent)
    {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            decodeStringMessage(it)
        }
    }

    private fun decodeStringMessage(messageString: String)
    {
        // Update UI to reflect text being shared
        val decodeResult = Codex().decode(messageString)

        if (decodeResult != null) {
            this.decodePayload = decodeResult.payload

            if (decodeResult.type == KeyOrMessage.EncryptedMessage) {
                // We received a message, have the user select who it is from
                val selectSenderIntent = Intent(this, SelectMessageSenderActivity::class.java)
                startActivityForResult(selectSenderIntent, RequestCodes.selectMessageSenderCode)
            } else {
                // We received a key, have the user select who it is from
                val selectSenderIntent = Intent(this, SelectKeySenderActivity::class.java)
                startActivityForResult(selectSenderIntent, RequestCodes.selectKeySenderCode)
            }
        } else {
            this.showAlert(getString(R.string.alert_text_unable_to_decode_message))
        }
    }

    @ExperimentalUnsignedTypes
    private fun handleSharedImage(intent: Intent)
    {
        val extraStream = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM)
        if (extraStream != null){
            (extraStream as? Uri)?.let {
                decodeImage(it)
            }
        }
        else {
            println("Extra Stream is Null")
        }
    }

    @ExperimentalUnsignedTypes
    private fun decodeImage(imageUri: Uri)
    {
        makeWait()

        val decodeResult: Deferred<ByteArray?> =
            coroutineScope.async(Dispatchers.IO) {
                val swatch = Decoder()
                return@async swatch.decode(applicationContext, imageUri)
            }

        coroutineScope.launch(Dispatchers.Main) {
            val maybeDecodeResult = decodeResult.await()

            noMoreWaiting()

            if (maybeDecodeResult != null) {
                handleDecodeImageResult(maybeDecodeResult)
            } else {
                applicationContext.showAlert(applicationContext.getString(R.string.alert_text_unable_to_process_request))
                print("Unable to decode the shared image.")
            }
        }
    }

    private fun handleDecodeImageResult(maybeBytes: ByteArray?)
    {
        if (maybeBytes != null) {
            this.decodePayload = maybeBytes
            val selectSenderIntent = Intent(this, SelectMessageSenderActivity::class.java)
            startActivityForResult(selectSenderIntent, RequestCodes.selectMessageSenderCode)
        } else {
            this.showAlert(getString(R.string.alert_text_unable_to_decode_message))
        }
    }

    private fun loadSavedFriends() {
        // Load our existing friends list from our encrypted file
        if (Persist.friendsFile.exists()) {
            val friendsToAdd = FriendViewModel.getFriends(Persist.friendsFile, applicationContext)

            for (newFriend in friendsToAdd) {
                // Only add this friend if the list does not contain a friend with that ID already
                if (!Persist.friendList.any { it.name == newFriend.name }) {
                    Persist.friendList.add(newFriend)
                }
            }
        }
    }

    private fun setupFriends() {
        Persist.friendsFile = File(filesDir.absolutePath + File.separator + friendsFilename )
        loadSavedFriends()
    }

    private fun loadSavedMessages() {
        Persist.messagesFile = File(filesDir.absolutePath + File.separator + messagesFilename )

        // Load messages from file
        if (Persist.messagesFile.exists()) {
            val messagesToAdd = MessageViewModel().getMessages(Persist.messagesFile, applicationContext)
            messagesToAdd?.let {
                Persist.messageList.clear()
                Persist.messageList.addAll(it)
            }

        }
    }

    private fun getStatus() {

        val statusString = Persist.encryptedSharedPreferences.getString(Persist.sharedPrefLoginStatusKey, null)

        status = if (statusString != null) {

            try {
                LoginStatus.valueOf(statusString)
            } catch (error: Exception) {
                print("Received invalid status from EncryptedSharedPreferences. User is logged out.")
                LoginStatus.LoggedOut
            }
        } else {
            LoginStatus.NotRequired
        }
    }

    // Logout Button Handler
    private fun logoutButtonClicked() {
        status = LoginStatus.LoggedOut
        Persist.saveLoginStatus()

        val returnToLoginIntent = Intent(this, EnterPasscodeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.flags = Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        startActivity(returnToLoginIntent)

        finish()
    }

    private fun makeWait()
    {
        homeProgressBar.visibility = View.VISIBLE
        read_button.isEnabled = false
        user_guide_button.isEnabled = false
        friends_button.isEnabled = false
        settings_button.isEnabled = false
        logout_button.isEnabled = false
        logout_button.isClickable = false
    }

    private fun noMoreWaiting()
    {
        homeProgressBar.visibility = View.INVISIBLE
        read_button.isEnabled = true
        user_guide_button.isEnabled = true
        friends_button.isEnabled = true
        settings_button.isEnabled = true
        logout_button.isEnabled = true
        logout_button.isClickable = true
    }

    private fun cleanUp () {
        decodePayload = null
    }
}
