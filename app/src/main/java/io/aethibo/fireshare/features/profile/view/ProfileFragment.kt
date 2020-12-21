package io.aethibo.fireshare.features.profile.view

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import by.kirich1409.viewbindingdelegate.viewBinding
import coil.load
import coil.transform.CircleCropTransformation
import com.google.firebase.auth.FirebaseAuth
import io.aethibo.fireshare.R
import io.aethibo.fireshare.core.utils.EventObserver
import io.aethibo.fireshare.databinding.FragmentProfileBinding
import io.aethibo.fireshare.features.profile.viewmodel.ProfileViewModel
import io.aethibo.fireshare.features.utils.BasePostFragment
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.viewmodel.ext.android.viewModel

class ProfileFragment : BasePostFragment(R.layout.fragment_profile), View.OnClickListener {

    private val binding: FragmentProfileBinding by viewBinding()
    private val viewModel: ProfileViewModel by viewModel()

    private val uid: String
        get() = FirebaseAuth.getInstance().uid!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.loadProfile(uid)

        setupRecyclerView()
        setupButtonClickListeners()
        subscribeToObservers()
        setupAdapter()
    }

    private fun setupAdapter() {
        lifecycleScope.launch {
            viewModel.getPagingFlow(uid).collect {
                postsAdapter.submitData(it)
            }
        }

        lifecycleScope.launch {
            postsAdapter.loadStateFlow.collectLatest {
                binding.profileHeader.profileProgressBar.isVisible = it.refresh is LoadState.Loading || it.append is LoadState.Loading
            }
        }

        binding.rvProfilePosts.apply {
            adapter = postsAdapter
            itemAnimator = null
        }
    }

    private fun subscribeToObservers() {
        viewModel.profileMeta.observe(viewLifecycleOwner, EventObserver(
                onLoading = {
                    binding.profileHeader.profileProgressBar.isVisible = true
                },
                onSuccess = { user ->
                    binding.profileHeader.profileProgressBar.isVisible = false
                    binding.profileHeader.tvProfileUsername.text = if (user.displayName.isEmpty()) user.username else user.displayName
                    binding.profileHeader.tvProfileBio.text = if (user.bio.isEmpty()) getString(R.string.no_description) else user.bio
//                    binding.tvProfileLocation.text = if (user.location.isEmpty()) getString(R.string.no_location) else user.location
                    binding.profileHeader.ivProfileAvatar.load(user.photoUrl) {
                        crossfade(true)
                        transformations(CircleCropTransformation())
                    }
                },
                onError = {
                    binding.profileHeader.profileProgressBar.isVisible = false
                }
        ))
    }

    private fun setupButtonClickListeners() {
        binding.profileHeader.btnProfileSettings.setOnClickListener(this)
    }

    private fun setupRecyclerView() {

    }

    override fun onClick(view: View?) {
        when (view?.id) {
            R.id.btnProfileSettings -> findNavController().navigate(R.id.settingsFragment)
        }
    }
}