package org.nahoft.nahoft.activities

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.android.synthetic.main.activity_friend_selection.*
import org.nahoft.nahoft.*
import org.nahoft.util.RequestCodes


class FriendSelectionActivity : AppCompatActivity() {

    private lateinit var linearLayoutManager: LinearLayoutManager
    private lateinit var adapter: FriendSelectionRecyclerAdapter

    companion object {
        fun newIntent(context: Context) = Intent(context, FriendSelectionActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friend_selection)

        // Only show friends that have been verified
        val approvedFriends = ArrayList<Friend>()

        for (friend in Persist.friendList) {

            if (friend.status == FriendStatus.Verified) {
                approvedFriends.add(friend)
            }
        }

        linearLayoutManager = LinearLayoutManager(this)
        adapter = FriendSelectionRecyclerAdapter(approvedFriends) {

            // This is the onClick listener for our Recycler
            val result = Intent()
            result.putExtra(RequestCodes.friendExtraTaskDescription, it)
            setResult(RESULT_OK, result)

            finish()
        }

        friend_selection_recycler_view.layoutManager = linearLayoutManager
        friend_selection_recycler_view.adapter = adapter
    }

    override fun onDestroy(){
        super.onDestroy()
        adapter.cleanup()
    }
}

