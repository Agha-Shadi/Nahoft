package org.nahoft.nahoft.activities

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.ContactsContract
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_home.*
import org.nahoft.codex.Codex
import org.nahoft.codex.KeyOrMessage
import org.nahoft.nahoft.*
import org.nahoft.nahoft.Persist.Companion.friendsFilename
import org.nahoft.nahoft.Persist.Companion.messagesFilename
import org.nahoft.nahoft.Persist.Companion.status
import org.nahoft.showAlert
import org.nahoft.stencil.Stencil
import org.nahoft.util.RequestCodes
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HomeActivity : AppCompatActivity() {
    private var decodePayload: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        Persist.app = Nahoft()

        if (status == LoginStatus.NotRequired) {
            logout_button.visibility = View.INVISIBLE
        } else {
            logout_button.visibility = View.VISIBLE
        }

        // Messages
        messages_button.setOnClickListener {
            val messagesIntent = Intent(this, MessagesActivity::class.java)
            startActivity(messagesIntent)
        }

        // User guides are not yet implemented
        user_guide_button.visibility = View.INVISIBLE
        user_guide.visibility = View.INVISIBLE
//        // User Guide
////        user_guide_button.setOnClickListener {
////            val userGuideIntent = Intent(this, UserGuideActivity::class.java)
////            startActivity(userGuideIntent)
////        }

        // Friends
        friends_button.setOnClickListener {
            val friendsIntent = Intent(this, FriendsActivity::class.java)
            startActivity(friendsIntent)
        }

        // Settings
        settings_button.setOnClickListener {
            val settingsIntent = Intent(this, SettingsActivity::class.java)
            startActivity(settingsIntent)
        }

        // Load friends from file and add any new contacts
        getStatus()
        setupFriends()
        loadSavedMessages()

        // Receive shared messages
        when (intent?.action) {
            Intent.ACTION_SEND -> {

                // Received shared data check LoginStatus
                if (status == LoginStatus.NotRequired || status == LoginStatus.LoggedIn) {
                    // We may not have initialized shared preferences yet, let's do it now
                    Persist.loadEncryptedSharedPreferences(this.applicationContext)
                } else if (status != LoginStatus.LoggedIn) {
                    //FIXME: If the status is not either NotRequired, or Logged in, request login
                    this.showAlert(getString(R.string.alert_text_passcode_required_to_proceed))
                }

                if ("text/plain" == intent.type) {
                    handleSharedText(intent)
                } else if (intent.type?.startsWith("image/") == true) {
                    handleSharedImage(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (status == LoginStatus.NotRequired) {
            logout_button.visibility = View.INVISIBLE
        } else {
            logout_button.visibility = View.VISIBLE
        }
    }

    // Logout Button Handler
    fun logoutClicked() {
        status = LoginStatus.LoggedOut
        Persist.saveLoginStatus()

        val returnToLoginIntent = Intent(this, EnterPasscodeActivity::class.java)
        startActivity(returnToLoginIntent)
    }

    private fun getStatus() {

        val statusString = Persist.encryptedSharedPreferences.getString(Persist.sharedPrefLoginStatusKey, null)

        if (statusString != null) {

            try {
                status = LoginStatus.valueOf(statusString)
            } catch (error: Exception) {
                print("Received invalid status from EncryptedSharedPreferences. User is logged out.")
                Persist.status = LoginStatus.LoggedOut
            }
        } else {
            Persist.status = LoginStatus.NotRequired
        }
    }

    private fun loadSavedFriends() {
        // Load our existing friends list from our encrypted file
        if (Persist.friendsFile.exists()) {
            val friendsToAdd = FriendViewModel.getFriends(Persist.friendsFile, applicationContext)

            for (newFriend in friendsToAdd) {
                // Only add this friend if the list does not contain a friend with that ID already
                if (!Persist.friendList.any { it.id == newFriend.id }) {
                    Persist.friendList.add(newFriend)
                }
            }
        }
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

    private fun handleSharedText(intent: Intent) {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            // Update UI to reflect text being shared
            val decodeResult = Codex().decode(it)

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
    }

    private fun handleSharedImage(intent: Intent) {
        (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
            // Update UI to reflect image being shared

            // DEV ONLY DO NOT TRANSLATE, DELETE LATER
            this.showAlert("Received shared image. $it")
            // DEV ONLY DO NOT TRANSLATE, DELETE LATER

            // Decode the message and save it locally for use after sender is selected
            this.decodePayload = Stencil().decode(this, it)

            // We received a message, have the user select who it is from
            val selectSenderIntent = Intent(this, SelectMessageSenderActivity::class.java)
            startActivityForResult(selectSenderIntent, RequestCodes.selectMessageSenderCode)
        }
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

            } else if (requestCode == RequestCodes.selectKeySenderCode) {
                val sender = data?.getSerializableExtra(RequestCodes.friendExtraTaskDescription)?.let { it as Friend }

                // Update this friend with a new key and a new status
                if (sender != null && decodePayload != null) {

                    when (sender.status) {
                        FriendStatus.Default -> {
                            Persist.updateFriend(context = this, friendToUpdate = sender, newStatus = FriendStatus.Requested, encodedPublicKey = decodePayload!!)
                            this.showAlert(getString(R.string.alert_text_received_invitation, sender.name))
                        }

                        FriendStatus.Invited -> {
                            Persist.updateFriend(context = this, friendToUpdate = sender, newStatus = FriendStatus.Approved, encodedPublicKey = decodePayload!!)
                            // TODO Translate
                            this.showAlert(sender.name,(R.string.alert_text_invitation_accepted))
                        }

                        else ->
                            this.showAlert(getString(R.string.alert_text_unable_to_update_friend_status))
                    }
                } else {
                    this.showAlert(getString(R.string.alert_text_unable_to_update_friend_status))
                }
            }
        }
    }

    // TODO: Move this
    private val permissionsRequestReadContacts = 100

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == permissionsRequestReadContacts) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                loadContacts()

            } else {
                println("No Permission For Contacts")
            }
        }
    }

    private fun setupFriends() {
        Persist.friendsFile = File(filesDir.absolutePath + File.separator + friendsFilename )
        loadSavedFriends()

        // Check contacts for new friends.
        loadContacts()
    }

    private fun loadContacts() {
        // Check if we have permission to see the user's contacts
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // We don't have permission, ask for it
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                permissionsRequestReadContacts
            )
        } else {
            // Permission granted!
            // Let's get the contacts and convert them to friends!
            getContacts()
        }
    }

    private fun getContacts() {
        val resolver: ContentResolver = contentResolver
        val cursor = resolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null)

        if (cursor != null) {
            if (cursor.count > 0) {
                while (cursor.moveToNext()) {
                    val id =
                        cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID))
                    val name =
                        cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                    val newFriend = Friend(id, name)

                    // TODO: Find a better way to do this,
                    //  after the first time this runs most contacts will already be in the friends list.

                    // Only add this friend if the list does not contain a friend with that ID already
                    if (!Persist.friendList.any { it.id == newFriend.id }) {
                        Persist.friendList.add(newFriend)
                    }
                }

                cursor.close()
                Persist.saveFriendsToFile(this)
            }
        } else {
            println("cursor is null")
        }
    }
}
