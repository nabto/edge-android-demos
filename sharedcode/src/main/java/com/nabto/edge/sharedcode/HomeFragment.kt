package com.nabto.edge.sharedcode

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitIsCurrentUserPaired
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.nabto.edge.sharedcode.*
import kotlinx.coroutines.Dispatchers

private object DeviceMenu {
    const val EDIT = 0
    const val DELETE = 1
}

/**
 * RecyclerView Adapter for updating the views per item in the list.
 */
class DeviceListAdapter(val context: Context) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View, val context: Context) : RecyclerView.ViewHolder(view), View.OnCreateContextMenuListener{
        lateinit var device: Device
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
        val connectionStatusView: ImageView = view.findViewById(R.id.home_device_item_connection)
        val progressBar: CircularProgressIndicator = view.findViewById(R.id.home_device_item_loading)

        init {
            view.setOnCreateContextMenuListener(this)
        }

        override fun onCreateContextMenu(
            menu: ContextMenu?,
            v: View?,
            menuInfo: ContextMenu.ContextMenuInfo?
        ) {
            val onEdit = MenuItem.OnMenuItemClickListener {
                v?.findFragment<HomeFragment>()?.onDeviceMenuClick(device, it.itemId)
                true
            }

            val items = listOf(
                menu?.add(Menu.NONE, DeviceMenu.EDIT, 1, context.getString(R.string.home_edit_friendly_name)),
                menu?.add(Menu.NONE, DeviceMenu.DELETE, 2, context.getString(R.string.home_remove_device))
            )

            for (it in items) {
                it?.setOnMenuItemClickListener(onEdit)
            }
        }
    }

    private var dataSet: List<DeviceBookmark> = listOf()

    fun submitDeviceList(devices: List<DeviceBookmark>) {
        dataSet = devices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_device_list_item, parent, false)
        return ViewHolder(view, context)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.device = dataSet[position].device
        holder.title.text = dataSet[position].device.getDeviceNameOrElse(context.getString(R.string.unnamed_device))
        holder.status.text = dataSet[position].device.deviceId
        holder.view.setOnClickListener {
            it.findFragment<HomeFragment>().onDeviceClick(dataSet[position].device)
        }

        val status = dataSet[position].status
        val (color, icon) = when (status) {
            BookmarkStatus.ONLINE -> R.color.green to R.drawable.ic_baseline_check_circle
            BookmarkStatus.UNPAIRED -> R.color.yellow to R.drawable.ic_baseline_lock
            BookmarkStatus.CONNECTING -> R.color.red to R.drawable.ic_baseline_warning
            BookmarkStatus.OFFLINE -> R.color.red to R.drawable.ic_baseline_warning
            BookmarkStatus.WRONG_FINGERPRINT -> R.color.red to R.drawable.ic_baseline_lock
        }

        if (status == BookmarkStatus.CONNECTING) {
            holder.connectionStatusView.swapWith(holder.progressBar)
        } else {
            holder.progressBar.swapWith(holder.connectionStatusView)
            holder.connectionStatusView.setImageResource(icon)
            holder.connectionStatusView.setColorFilter(
                ContextCompat.getColor(
                    holder.view.context,
                    color
                )
            )
        }
    }

    override fun getItemCount() = dataSet.size
}


/**
 * Fragment for fragment_home.xml.
 */
class HomeFragment : Fragment(), MenuProvider {
    private val TAG = javaClass.simpleName

    private val database: DeviceDatabase by inject()
    private val bookmarks: NabtoBookmarksRepository by inject()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        val deviceListAdapter = DeviceListAdapter(requireContext())

        val recycler = view.findViewById<RecyclerView>(R.id.home_recycler)
        val layoutManager = LinearLayoutManager(activity)
        recycler.adapter = deviceListAdapter
        recycler.layoutManager = layoutManager

        view.findViewById<Button>(R.id.home_pair_new_button).setOnClickListener {
            findNavController().navigate(AppRoute.pairingFlow())
        }

        bookmarks.getDevices().observe(viewLifecycleOwner, Observer { devices ->
            deviceListAdapter.submitDeviceList(devices)
            view.findViewById<View>(R.id.home_empty_layout).visibility =
                if (devices.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        })

        val dividerItemDecoration = DividerItemDecoration(
            recycler.context,
            layoutManager.orientation
        )
        recycler.addItemDecoration(dividerItemDecoration)
        registerForContextMenu(recycler)
    }

    override fun onResume() {
        super.onResume()
        bookmarks.synchronize()
    }

    fun onDeviceClick(device: Device) {
        val status = bookmarks.getStatus(device)
        if (status == BookmarkStatus.UNPAIRED) {
            bookmarks.releaseAll()
            findNavController().navigate(AppRoute.pairDevice(device.productId, device.deviceId))
        } else if (status == BookmarkStatus.WRONG_FINGERPRINT) {
            view?.snack("Fingerprint is different from expected! Please delete and redo pairing if this is deliberate.")
        } else {
            bookmarks.releaseAllExcept(listOf(device))
            findNavController().navigate(AppRoute.appDevicePage(device.productId, device.deviceId))
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_device_refresh) {
            bookmarks.reconnect()
            return true
        }
        return false
    }

    fun onDeviceMenuClick(device: Device, id: Int) {
        when (id) {
            DeviceMenu.EDIT -> {
                AlertDialog.Builder(context).apply {
                    val et = EditText(context)
                    et.setText(device.friendlyName)
                    setTitle(R.string.home_edit_friendly_name)
                    setView(et)

                    setPositiveButton(getString(R.string.confirm)) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val dao = database.deviceDao()
                            dao.insertOrUpdate(device.copy(
                                friendlyName = et.text.toString()
                            ))
                        }
                    }

                    setNegativeButton(getString(R.string.cancel)) { _, _ ->

                    }

                    create()
                    show()
                }
            }

            DeviceMenu.DELETE -> {
                AlertDialog.Builder(context).apply {
                    setTitle(R.string.home_remove_device)
                    setMessage(getString(R.string.home_delete_dialog_message, device.friendlyName))

                    setPositiveButton(getString(R.string.confirm)) { _, _ ->
                        view?.snack(getString(R.string.home_delete_device_snack, device.friendlyName))
                        lifecycleScope.launch(Dispatchers.IO) {
                            val dao = database.deviceDao()
                            dao.delete(device)
                        }
                    }

                    setNegativeButton(getString(R.string.cancel)) { _, _ -> }

                    create()
                    show()
                }
            }
        }
    }
}