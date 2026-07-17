package com.example.nokidcamera;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BleDeviceAdapter extends RecyclerView.Adapter<BleDeviceAdapter.ViewHolder> {

    public interface OnDeviceClickListener {
        void onDeviceClick(String name, String address);
    }

    private final List<String[]> devices = new ArrayList<>(); // [name, address]
    private final OnDeviceClickListener listener;

    public BleDeviceAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    public void addDevice(String name, String address) {
        // 중복 체크
        for (String[] d : devices) {
            if (d[1].equals(address)) return;
        }
        devices.add(new String[]{name, address});
        notifyItemInserted(devices.size() - 1);
    }

    public void clear() {
        devices.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String[] device = devices.get(position);
        holder.text1.setText(device[0]);
        holder.text2.setText(device[1]);
        holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device[0], device[1]));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1, text2;
        ViewHolder(View view) {
            super(view);
            text1 = view.findViewById(android.R.id.text1);
            text2 = view.findViewById(android.R.id.text2);
        }
    }
}
