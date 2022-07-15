package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.findFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.ktx.connectAsync
import com.nabto.edge.iamutil.Iam
import com.nabto.edge.iamutil.IamException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.android.ext.android.inject

class PairingDeviceListAdapter(lifecycleOwner: LifecycleOwner, repo: NabtoRepository) : RecyclerView.Adapter<PairingDeviceListAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.textView)
        var device: Device? = null

        init {
            view.setOnClickListener { v ->
                v.findFragment<InitialPairingFragment>().onDeviceClicked(device)
            }
        }
    }

    private var dataSet: List<Device> = listOf()

    init {
        repo.getScannedDevices().observe(lifecycleOwner) { t ->
            dataSet = t
            for (i in 0..dataSet.size) {
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.text_row_item, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.textView.text = dataSet[position].deviceId
        viewHolder.device = dataSet[position]
    }

    override fun getItemCount() = dataSet.size
}

class InitialPairingFragment : Fragment() {
    private val database: DeviceDatabase by inject()
    private val repo: NabtoRepository by inject()
    private val service: NabtoConnectionService by inject()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_initial_pairing, container, false)

        val recycler = view.findViewById<RecyclerView>(R.id.initial_pairing_recycler)
        recycler.adapter = PairingDeviceListAdapter(viewLifecycleOwner, repo)
        recycler.layoutManager = LinearLayoutManager(activity)

        return view
    }

    fun onDeviceClicked(device: Device?) {
        if (device != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val activity = this@InitialPairingFragment.requireActivity()
                    val connection = service.createConnection()
                    val options = JSONObject()
                    options.put("ProductId", device.productId)
                    options.put("DeviceId", device.deviceId)
                    options.put("ServerKey", repo.getClientPrivateKey())
                    options.put("PrivateKey", repo.getServerKey())
                    connection.updateOptions(options.toString())
                    connection.connectAsync()

                    try {
                        connection.connect()
                        val iam = Iam.create()
                        val isPaired = iam.isCurrentUserPaired(connection)

                        if (!isPaired) {
                            iam.pairLocalInitial(connection)
                            val dao = database.deviceDao()
                            // @TODO: Let the user choose a friendly name for the device before inserting
                            dao.insertOrUpdate(device)
                            val snackbar = Snackbar.make(
                                this@InitialPairingFragment.requireView(),
                                "Successfully paired",
                                Snackbar.LENGTH_LONG
                            )
                            snackbar.show()
                        }
                        else {
                            val snackbar = Snackbar.make(
                                this@InitialPairingFragment.requireView(),
                                "Already paired",
                                Snackbar.LENGTH_LONG
                            )
                            snackbar.show()
                        }
                        connection.close()
                    }
                    catch (e: IamException) {
                        Log.i("DeviceDebug", "IamException while pairing: ${e.error.name}")
                    }
                    catch (e: NabtoRuntimeException) {
                        Log.i("DeviceDebug", "NabtoRuntimeException while pairing: ${e.errorCode}")
                    }
                }
            }
        }
    }
}