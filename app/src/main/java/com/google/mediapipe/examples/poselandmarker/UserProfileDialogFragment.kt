package com.google.mediapipe.examples.poselandmarker

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class UserProfileDialogFragment : DialogFragment() {
    private val isFirstLaunch get() = requireArguments().getBoolean(ARG_FIRST_LAUNCH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = !isFirstLaunch
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_user_profile, null)
        val age = content.findViewById<EditText>(R.id.etAge)
        val height = content.findViewById<EditText>(R.id.etHeight)
        val weight = content.findViewById<EditText>(R.id.etWeight)
        val gender = content.findViewById<RadioGroup>(R.id.rgGender)
        content.findViewById<TextView>(R.id.tvProfileTitle).text =
            if (isFirstLaunch) "Configura tu perfil" else "Editar perfil"
        val cancel = content.findViewById<Button>(R.id.btnCancelProfile)
        cancel.visibility = if (isFirstLaunch) View.GONE else View.VISIBLE
        cancel.setOnClickListener { dismiss() }

        if (!isFirstLaunch) UserProfileRepository(requireContext()).getProfile()?.let { profile ->
            age.setText(profile.edad.toString())
            height.setText(profile.alturaCm.toString())
            weight.setText(profile.pesoKg.toString())
            gender.check(when (profile.genero) {
                "Masculino" -> R.id.rbMale
                "Femenino" -> R.id.rbFemale
                else -> R.id.rbOther
            })
        }

        val dialog = AlertDialog.Builder(requireContext()).setView(content).create()
        dialog.setCanceledOnTouchOutside(!isFirstLaunch)
        content.findViewById<Button>(R.id.btnSaveProfile).setOnClickListener {
            val parsedAge = age.text.toString().toIntOrNull()
            if (parsedAge == null || parsedAge !in 10..100) { age.error = "Ingresa una edad válida (10-100)"; return@setOnClickListener }
            val parsedHeight = height.text.toString().toIntOrNull()
            if (parsedHeight == null || parsedHeight !in 100..250) { height.error = "Ingresa una altura válida en cm (100-250)"; return@setOnClickListener }
            val parsedWeight = weight.text.toString().replace(',', '.').toFloatOrNull()
            if (parsedWeight == null || parsedWeight < 25f || parsedWeight > 300f) {
                weight.error = "Ingresa un peso válido en kg (25-300)"; return@setOnClickListener
            }
            val selectedGender = when (gender.checkedRadioButtonId) {
                R.id.rbMale -> "Masculino"
                R.id.rbFemale -> "Femenino"
                R.id.rbOther -> "Otro"
                else -> null
            }
            if (selectedGender == null) {
                Toast.makeText(requireContext(), "Selecciona un género", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            UserProfileRepository(requireContext()).saveProfile(UserProfile(parsedAge, parsedHeight, parsedWeight, selectedGender))
            Toast.makeText(requireContext(), "Perfil guardado", Toast.LENGTH_SHORT).show()
            dismiss()
        }
        return dialog
    }

    companion object {
        private const val ARG_FIRST_LAUNCH = "is_first_launch"
        fun newInstance(isFirstLaunch: Boolean) = UserProfileDialogFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_FIRST_LAUNCH, isFirstLaunch) }
        }
    }
}
